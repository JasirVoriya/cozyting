package com.cozyting.app.capture.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperHeartbeatPolicyTest {
    @Test
    fun toleratesTransientTimeoutsAndResetsOnSuccess() {
        val policy = HelperHeartbeatPolicy(maxConsecutiveTimeouts = 3)

        assertTrue(policy.onHeartbeatTimeout())
        assertTrue(policy.onHeartbeatTimeout())
        assertEquals(2, policy.consecutiveTimeoutCount())

        policy.onHeartbeatSuccess()
        assertEquals(0, policy.consecutiveTimeoutCount())

        assertTrue(policy.onHeartbeatTimeout())
        assertTrue(policy.onHeartbeatTimeout())
        assertTrue(policy.onHeartbeatTimeout())
        assertFalse(policy.onHeartbeatTimeout())
        assertEquals(4, policy.consecutiveTimeoutCount())
    }

    @Test
    fun enforcesSlidingWindowBudgetForAutoRespawn() {
        val policy = HelperHeartbeatPolicy(
            respawnWindowMs = 30_000L,
            maxRespawnAttempts = 2,
        )

        assertTrue(policy.tryAcquireRespawnBudget(1_000L))
        assertTrue(policy.tryAcquireRespawnBudget(10_000L))
        assertFalse(policy.tryAcquireRespawnBudget(20_000L))

        // First attempt at t=1_000L expires after t=31_000L
        assertTrue(policy.tryAcquireRespawnBudget(31_001L))
        assertFalse(policy.tryAcquireRespawnBudget(35_000L))

        policy.resetAll()
        assertTrue(policy.tryAcquireRespawnBudget(35_001L))
    }
}
