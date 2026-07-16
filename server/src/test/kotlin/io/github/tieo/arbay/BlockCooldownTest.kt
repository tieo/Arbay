package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.BlockCooldown
import io.github.tieo.arbay.crawler.ErrorType
import io.github.tieo.arbay.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlockCooldownTest {

    @Test
    fun blockErrorTypesTriggerCooldown() {
        assertTrue(BlockCooldown.isBlock(ErrorType.BLOCKED_403))
        assertTrue(BlockCooldown.isBlock(ErrorType.CAPTCHA))
        assertTrue(BlockCooldown.isBlock(ErrorType.RATE_LIMITED_429))
        assertFalse(BlockCooldown.isBlock(ErrorType.EMPTY_RESULTS))
        assertFalse(BlockCooldown.isBlock(ErrorType.PARSE_ERROR))
    }

    @Test
    fun recordingABlockStartsAndClearsCooldown() {
        // Use a platform not touched elsewhere to keep the test isolated.
        val p = PlatformId.TRUCKSCOUT24
        assertFalse(BlockCooldown.isCoolingDown(p))
        BlockCooldown.record(p)
        assertTrue(BlockCooldown.isCoolingDown(p))
        assertTrue(BlockCooldown.remainingMs(p) > 0)
    }
}
