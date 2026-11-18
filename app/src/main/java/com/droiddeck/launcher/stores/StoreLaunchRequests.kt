package com.droiddeck.launcher.stores

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import org.json.JSONObject

object StoreLaunchRequests {
    private const val TAG = "StoreLaunchRequests"
    private val lock = Any()
    private var observer: FileObserver? = null

    private val hostLaunch = StoreLaunchAuthorization()

    /** Called only from host-controlled game launch paths, never from guest requests. */
    fun noteHostLaunch(store: Store, id: String, steamGameId: String? = null) =
        hostLaunch.record(store, id, steamGameId)

    fun hostSteamGameQueued(gameId: String) = hostLaunch.steamGameQueued(gameId)

    fun finishHostSteamGame(gameId: String) = hostLaunch.finishSteamGame(gameId)

    fun clearHostLaunch() = hostLaunch.reset()

    fun hostLaunchStore(): Store? = hostLaunch.activeStore()

    private fun launched(store: Store, id: String) = hostLaunch.allows(store, id)

    fun dir(context: Context) = File(LinuxRuntime.sessionRoot(context), "stores")

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            observer?.stopWatching()
            val root = dir(app)
            root.deleteRecursively()
            // StoreLaunch.prepare() may run immediately before SessionService
            // starts the guest; preserve that host-issued launch grant here.
            // stop() and the grant's lifetime handle stale permissions.
            val req = File(root, "req").apply { mkdirs() }
            File(root, "resp").mkdirs()
            @Suppress("DEPRECATION")
            observer = object : FileObserver(req.path, MOVED_TO or CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || !path.endsWith(".json")) return
                    val file = File(req, path)
                    Thread({ answer(app, file) }, "store-launch-request").start()
                }
            }.also { it.startWatching() }
        }
    }

    fun stop() {
        com.droiddeck.launcher.stores.xbox.XboxRuntime.stop()
        com.droiddeck.launcher.stores.ea.EaLsx.stop()
        synchronized(lock) {
            observer?.stopWatching()
            observer = null
            hostLaunch.reset()
        }
    }

    private fun answer(app: Context, file: File) {
        val request = try {
            JSONObject(file.readText()).also { file.delete() }
        } catch (e: Exception) {
            return
        }
        val reply = when (request.optString("op")) {
            "xbox-released" -> {
                com.droiddeck.launcher.stores.xbox.XboxRuntime.released(request.optString("lease"))
                JSONObject().put("ok", true)
            }
            "xbox-renew" -> JSONObject().put("ok", com.droiddeck.launcher.stores.xbox.XboxRuntime.renew(app, request.optString("id"), request.optString("lease")))
            "xbox-prepare" -> runCatching {
                val id = request.getString("id")
                if (!launched(Store.XBOX, id)) JSONObject().put("ok", false).put("reason", "not-launched")
                else com.droiddeck.launcher.stores.xbox.XboxRuntime.prepare(app, id)
            }
                .getOrElse {
                    StoresState.logLine("Xbox: launch prepare failed (${it.javaClass.simpleName}: ${it.message})")
                    val reason = (it as? IllegalStateException)?.message ?: "Xbox account service is unavailable. Sign in again and retry."
                    JSONObject().put("ok", false).put("reason", reason)
                }
            "ea-prepare" -> runCatching {
                val id = request.optString("id")
                if (!launched(Store.EA, id)) JSONObject().put("ok", false).put("reason", "not-launched")
                else com.droiddeck.launcher.stores.ea.EaLaunch.prepare(app, request)
            }
                .getOrElse { JSONObject().put("ok", false).put("reason", "error-" + it.javaClass.simpleName) }
            "ea-exited" -> {
                val id = request.optString("id")
                val allowed = launched(Store.EA, id)
                if (allowed) {
                    com.droiddeck.launcher.stores.ea.EaLsx.stop(id)
                    hostLaunch.finish(Store.EA, id)
                }
                JSONObject().put("ok", allowed)
            }
            "gog-achievements" -> runCatching {
                val id = request.optString("id")
                if (!launched(Store.GOG, id)) JSONObject().put("ok", false).put("ready", false).put("reason", "not-launched")
                else com.droiddeck.launcher.stores.gog.GogComet.prepare(app, id)
            }
                .getOrElse { JSONObject().put("ok", false).put("ready", false).put("reason", "service-unavailable") }
            "epic-code" -> {
                val id = request.optString("id")
                if (id.isEmpty()) JSONObject().put("ok", false).put("reason", "no-id")
                else if (!launched(Store.EPIC, id)) JSONObject().put("ok", false).put("reason", "not-launched")
                else {
                    val code = StoreLaunch.epicCode(app, id)
                    JSONObject().put("ok", true).put("code", code.written).put("reason", code.reason)
                }
            }
            "rockstar-launch" -> runCatching {
                val id = request.optString("id")
                if (!launched(Store.ROCKSTAR, id)) JSONObject().put("ok", false).put("reason", "not-launched")
                else com.droiddeck.launcher.stores.rockstar.RockstarLaunch.prepare(app, id)
            }.getOrElse { JSONObject().put("ok", false).put("reason", "error-" + it.javaClass.simpleName) }
            "store-service" -> runCatching {
                val root = com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(app)
                if (com.droiddeck.launcher.session.SessionFiles.awaitStoreService(app, request.optString("store"), root)) JSONObject().put("ok", true)
                else JSONObject().put("ok", false).put("reason", "service-unavailable")
            }.getOrElse { JSONObject().put("ok", false).put("reason", "error-" + it.javaClass.simpleName) }
            "rockstar-exited" -> {
                val id = request.optString("id")
                val allowed = launched(Store.ROCKSTAR, id)
                if (allowed) hostLaunch.finish(Store.ROCKSTAR, id)
                JSONObject().put("ok", allowed)
            }
            "ubisoft-launch" -> {
                val id = request.optString("id")
                if (id.isEmpty()) JSONObject().put("ok", false).put("reason", "no-id")
                else if (!launched(Store.UBISOFT, id)) JSONObject().put("ok", false).put("reason", "not-launched")
                else {
                    runCatching { com.droiddeck.launcher.stores.ubisoft.UbisoftLaunch.prepare(app, id) }
                        .getOrElse { JSONObject().put("ok", false).put("reason", it.javaClass.simpleName) }
                }
            }
            "cloud-down" -> {
                val store = Store.byId(request.optString("store"))
                val id = request.optString("id")
                if (store == null || id.isEmpty()) JSONObject().put("ok", false).put("reason", "no-game")
                else if (!launched(store, id)) JSONObject().put("ok", false).put("reason", "not-launched")
                else {
                    CloudSaves.markDirty(app, store, id)
                    CloudSaves.downloadOrDefer(app, store, id).let { JSONObject().put("ok", it.ok).put("result", it.result).put("files", it.files).put("reason", it.reason) }
                }
            }
            "cloud-up" -> {
                val store = Store.byId(request.optString("store"))
                val id = request.optString("id")
                val allowed = store != null && id.isNotEmpty() && launched(store, id)
                if (allowed) Thread({ CloudSaves.uploadDirty(app, store!!, id, "exit") }, "cloud-up").start()
                JSONObject().put("ok", allowed)
            }
            "open-url" -> {
                val url = request.optString("url")
                if (!(url.startsWith("https://") || url.startsWith("http://"))) JSONObject().put("ok", false).put("reason", "not-http")
                else try {
                    app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    com.droiddeck.launcher.session.SessionService.holdForBrowserHandoff(app)
                    Log.i(TAG, "open-url: " + StoreLog.redactUrl(url))
                    JSONObject().put("ok", true)
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("reason", e.javaClass.simpleName)
                }
            }
            "epic-sign-in" -> {
                val url = request.optString("url")
                try {
                    if (com.droiddeck.launcher.stores.epic.EpicClient.signIn(app, url, request.optBoolean("auto"))) JSONObject().put("ok", true)
                    else JSONObject().put("ok", false).put("reason", "not-sign-in")
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("reason", e.javaClass.simpleName)
                }
            }
            "epic-play-in-steam" -> {
                val id = request.optString("id")
                if (id.isEmpty()) JSONObject().put("ok", false).put("reason", "no-id")
                else try {
                    com.droiddeck.launcher.stores.epic.EpicClient.playInSteam(app, id)
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("reason", e.javaClass.simpleName)
                }
            }
            "steam-run" -> try {
                com.droiddeck.launcher.stores.epic.EpicClient.runSteamGame(app, request.optString("id"))
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("reason", e.javaClass.simpleName)
            }
            "epic-signed-out" -> {
                val signedIn = com.droiddeck.launcher.stores.epic.EpicCredentialStore.isLoggedIn(app)
                if (signedIn) {
                    Log.i(TAG, "the Epic Games Launcher was signed out; signing the Epic store out too")
                    StoresState.signOut(app, Store.EPIC)
                }
                JSONObject().put("ok", true).put("signedOut", signedIn)
            }
            "epic-client-handoff" -> {
                if (com.droiddeck.launcher.stores.epic.EpicClient.canHandOff(app)) JSONObject().put("ok", true)
                else JSONObject().put("ok", false).put("reason", "no-account")
            }
            else -> JSONObject().put("ok", false).put("reason", "unknown-op")
        }
        try {
            val resp = File(dir(app), "resp").apply { mkdirs() }
            val tmp = File(resp, file.name + ".tmp")
            tmp.writeText(reply.toString())
            tmp.renameTo(File(resp, file.name))
        } catch (e: Exception) {
            Log.w(TAG, "could not answer ${file.name}: ${e.message}")
        }
    }
}
