package com.droiddeck.launcher.stores

import org.junit.Assert.*
import org.junit.Test

class StoreLaunchAuthorizationTest {
    @Test fun onlyHostGrantedGameCanUseTheBridge() {
        var now = 1000L
        val gate = StoreLaunchAuthorization { now }
        assertFalse(gate.allows(Store.EA, "ea-one"))
        gate.record(Store.EA, "ea-one", "1234")
        assertTrue(gate.allows(Store.EA, "ea-one"))
        assertFalse(gate.allows(Store.EA, "ea-two"))
        assertFalse(gate.allows(Store.UBISOFT, "ea-one"))
        gate.steamGameQueued("1234")
        assertTrue(gate.allows(Store.EA, "ea-one"))
        gate.finishSteamGame("9999")
        assertTrue(gate.allows(Store.EA, "ea-one"))
        gate.finishSteamGame("1234")
        assertFalse(gate.allows(Store.EA, "ea-one"))
    }

    @Test fun grantsAreRevokedAndExpire() {
        var now = 1000L
        val gate = StoreLaunchAuthorization { now }
        gate.record(Store.ROCKSTAR, "r1")
        assertEquals(Store.ROCKSTAR, gate.activeStore())
        gate.finish(Store.ROCKSTAR, "another")
        assertTrue(gate.allows(Store.ROCKSTAR, "r1"))
        gate.finish(Store.ROCKSTAR, "r1")
        assertFalse(gate.allows(Store.ROCKSTAR, "r1"))
        gate.record(Store.EPIC, "e1")
        gate.steamGameQueued("untrusted-guest-queue")
        assertFalse(gate.allows(Store.EPIC, "e1"))
        gate.record(Store.EPIC, "e1")
        now += 12L * 60 * 60 * 1000
        assertFalse(gate.allows(Store.EPIC, "e1"))
        assertNull(gate.activeStore())
        gate.record(Store.EPIC, "e1")
        gate.reset()
        assertFalse(gate.allows(Store.EPIC, "e1"))
    }

    @Test fun replacingAnEaLaunchRevokesItsGrantEvenWhenTheGameIsTheSame() {
        val revoked = mutableListOf<StoreLaunchAuthorization.Launch>()
        val gate = StoreLaunchAuthorization(onRevoked = { revoked.add(it) }, nowMillis = { 1000L })
        gate.record(Store.EA, "ea-one", "1234")
        val first = gate.grant(Store.EA, "ea-one")!!

        gate.record(Store.EA, "ea-one", "1234")
        val second = gate.grant(Store.EA, "ea-one")!!
        assertEquals(listOf(first), revoked)
        assertNull(gate.withGrant(first) { error("Revoked grant may not open LSX") })
        assertEquals("ready", gate.withGrant(second) { "ready" })

        gate.finishSteamGame("1234")
        assertEquals(listOf(first, second), revoked)
    }

    @Test fun expiredGrantRunsCleanupWhenTheGameExits() {
        var now = 1000L
        val revoked = mutableListOf<String>()
        val gate = StoreLaunchAuthorization(onRevoked = { revoked.add(it.id) }, nowMillis = { now })
        gate.record(Store.EA, "ea-one")

        now += 12L * 60 * 60 * 1000
        assertFalse(gate.finish(Store.EA, "ea-one"))
        assertEquals(listOf("ea-one"), revoked)
    }
}
