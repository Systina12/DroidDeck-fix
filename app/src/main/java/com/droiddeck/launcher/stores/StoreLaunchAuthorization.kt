package com.droiddeck.launcher.stores

/**
 * Host-owned permission to prepare the most recently launched store game.
 *
 * The guest request bridge is writable by every guest process. Requests must
 * never create or extend this permission. A permission expires even if exit
 * notification is missed, and is revoked on the matching game exit.
 */
internal class StoreLaunchAuthorization(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Launch(
        val store: Store,
        val id: String,
        val steamGameId: String?,
        val atMillis: Long,
    )
    private var current: Launch? = null

    @Synchronized fun record(store: Store, id: String, steamGameId: String? = null) {
        if (id.isBlank()) return
        current = Launch(store, id, steamGameId, nowMillis())
    }

    @Synchronized fun allows(store: Store, id: String): Boolean {
        val launch = current ?: return false
        val elapsed = nowMillis() - launch.atMillis
        if (elapsed < 0 || elapsed >= MAX_AGE_MS) {
            current = null
            return false
        }
        return launch.store == store && launch.id == id
    }

    @Synchronized fun activeStore(): Store? {
        val launch = current ?: return null
        return launch.store.takeIf { allows(launch.store, launch.id) }
    }

    @Synchronized fun finish(store: Store, id: String) {
        if (current?.let { it.store == store && it.id == id } == true) current = null
    }

    @Synchronized fun finishSteamGame(gameId: String) {
        if (current?.steamGameId == gameId) current = null
    }

    @Synchronized fun reset() { current = null }

    companion object {
        private const val MAX_AGE_MS = 12L * 60 * 60 * 1000
    }
}
