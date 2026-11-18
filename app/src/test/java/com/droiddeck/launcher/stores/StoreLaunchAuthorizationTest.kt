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
}
