package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.PlatformId
import kotlinx.datetime.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * After a platform blocks us (403/captcha/429/503), stop hitting it for a while. Re-crawling a
 * site that just blocked only deepens the block and risks a longer ban — the opposite of what we
 * want. During the cooldown the search routes skip the platform and report it as cooling down;
 * cached results still serve. The window clears itself once elapsed, so the platform is retried
 * automatically on the next search after it.
 */
object BlockCooldown {

    private const val COOLDOWN_MS = 20 * 60 * 1000L // 20 minutes

    private val until = ConcurrentHashMap<PlatformId, Long>()

    /** Error types that mean the site actively rejected us — worth backing off from. */
    fun isBlock(type: ErrorType): Boolean = type in setOf(
        ErrorType.BLOCKED_403, ErrorType.CAPTCHA, ErrorType.RATE_LIMITED_429,
        ErrorType.AUTH_REQUIRED_401, ErrorType.SERVICE_UNAVAILABLE_503,
    )

    fun record(platform: PlatformId) {
        until[platform] = Clock.System.now().toEpochMilliseconds() + COOLDOWN_MS
    }

    /** Remaining cooldown in ms, or 0 when the platform is clear (also clears an elapsed entry). */
    fun remainingMs(platform: PlatformId): Long {
        val u = until[platform] ?: return 0
        val remaining = u - Clock.System.now().toEpochMilliseconds()
        if (remaining <= 0) {
            until.remove(platform)
            return 0
        }
        return remaining
    }

    fun isCoolingDown(platform: PlatformId): Boolean = remainingMs(platform) > 0
}
