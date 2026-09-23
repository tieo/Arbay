package io.github.tieo.arbay.classifier

import org.slf4j.Logger

/**
 * A value that is loaded on first use, like `lazy`, except that a failed load is tried again.
 *
 * The models are downloaded on first use. `lazy` keeps whatever the first attempt returned, so a
 * download that failed once (the network away for a minute after a restart) left the model off
 * until the next restart. Here a failure is remembered for [retryAfterMs] and the load then runs
 * again on the next use.
 */
internal class Reloadable<T : Any>(
    private val what: String,
    private val log: Logger,
    private val retryAfterMs: Long = 10 * 60_000L,
    private val load: () -> T,
) {
    @Volatile private var value: T? = null
    private var failedAtMs: Long? = null

    fun get(): T? {
        value?.let { return it }
        synchronized(this) {
            value?.let { return it }
            val failedAt = failedAtMs
            if (failedAt != null && System.currentTimeMillis() - failedAt < retryAfterMs) return null
            return try {
                load().also {
                    value = it
                    failedAtMs = null
                }
            } catch (e: Exception) {
                failedAtMs = System.currentTimeMillis()
                log.error("{} unavailable, trying again in {} min: {}", what, retryAfterMs / 60_000, e.message)
                null
            }
        }
    }
}
