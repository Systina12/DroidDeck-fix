package com.droiddeck.launcher.stores

/**
 * Host-owned permission to prepare the most recently launched store game.
 *
 * The guest request bridge is writable by every guest process. Requests must
 * never create or extend this permission. A permission expires even if exit
 * notification is missed, and is revoked on the matching game exit.
 */
internal class StoreLaunchAuthorization(
    private val onRevoked: (Launch) -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    class Launch(
        val store: Store,
        val id: String,
        val steamGameId: String?,
        val atMillis: Long,
    )
    private var current: Launch? = null

    @Synchronized fun record(store: Store, id: String, steamGameId: String? = null) {
        if (id.isBlank()) return
        reset()
        current = Launch(store, id, steamGameId, nowMillis())
    }

    private fun liveLaunch(): Launch? {
        val launch = current ?: return null
        val elapsed = nowMillis() - launch.atMillis
        if (elapsed < 0 || elapsed >= MAX_AGE_MS) {
            reset()
            return null
        }
        return launch
    }

    @Synchronized fun grant(store: Store, id: String): Launch? =
        liveLaunch()?.takeIf { it.store == store && it.id == id }

    fun allows(store: Store, id: String): Boolean = grant(store, id) != null

    @Synchronized fun <T : Any> withGrant(launch: Launch, action: () -> T): T? =
        if (liveLaunch() === launch) action() else null

    @Synchronized fun activeStore(): Store? = liveLaunch()?.store

    @Synchronized fun finish(store: Store, id: String): Boolean {
        if (grant(store, id) == null) return false
        reset()
        return true
    }

    /** A Steam launch request may also originate in the guest.
     * It must never create a host permission; keep only a permission previously
     * established by the Android library's Play action for this exact game.
     */
    @Synchronized fun steamGameQueued(gameId: String) {
        if (current?.steamGameId != gameId) reset()
    }

    @Synchronized fun finishSteamGame(gameId: String) {
        if (current?.steamGameId == gameId) reset()
    }

    @Synchronized fun reset() {
        val previous = current ?: return
        current = null
        onRevoked(previous)
    }

    companion object {
        private const val MAX_AGE_MS = 12L * 60 * 60 * 1000
    }
}
