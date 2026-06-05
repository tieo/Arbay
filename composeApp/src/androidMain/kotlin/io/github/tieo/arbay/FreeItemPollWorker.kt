package io.github.tieo.arbay

import android.content.Context
import androidx.work.*
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.model.NotificationSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic worker that polls the server for new free item matches
 * and fires notifications on the appropriate channels.
 *
 * Runs even when the app is in the background / killed.
 */
class FreeItemPollWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val client = ArbayClient()
            val result = client.pollNow()

            NotificationHelper.ensureChannels(applicationContext)

            // Urgent matches — immediate per-item notification
            for (match in result.urgentMatches) {
                val pct = ((match.relevanceScore ?: 0.0) * 100).toInt()
                NotificationHelper.showUrgentMatch(
                    title = "${pct}% match: ${match.title}",
                    body = match.locationText ?: match.description ?: "New high-confidence match",
                    url = match.url,
                )
            }

            // Novel items — separate channel
            for (item in result.novelItems) {
                NotificationHelper.showNovelItem(
                    title = "Something new: ${item.title}",
                    body = item.locationText ?: item.description ?: "Unlike anything you've seen before",
                    url = item.url,
                )
            }

            // Digest — summary notification for items above digest threshold
            if (result.digestMatches.isNotEmpty()) {
                // Only show digest if there are matches NOT already covered by urgent
                val urgentIds = result.urgentMatches.map { it.listingId }.toSet()
                val digestOnly = result.digestMatches.filter { it.listingId !in urgentIds }
                if (digestOnly.isNotEmpty()) {
                    NotificationHelper.showDigest(
                        count = digestOnly.size,
                        topTitle = digestOnly.firstOrNull()?.title,
                    )
                }
            }

            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "free_item_poll"

        /** Schedule periodic polling. Call from MainActivity or when settings change. */
        fun schedule(context: Context, intervalMinutes: Int = 60) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<FreeItemPollWorker>(
                intervalMinutes.toLong(), TimeUnit.MINUTES,
                // Flex window: run anytime in the last 15min of the interval
                15L, TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /** Cancel polling. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
