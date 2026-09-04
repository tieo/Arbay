package io.github.tieo.arbay

import android.content.Context
import androidx.work.*
import io.github.tieo.arbay.api.ArbayClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Asks the server, on the schedule set in the app, whether either of the two things worth
 * interrupting for has appeared, and raises them as notifications on this device.
 *
 * Runs while the app is in the background or killed, which is the point: both kinds of find are
 * gone by the time an app that only looks while open would see them.
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

            // A free item near you, above the score you set. Says the score, because that is the
            // whole reason this one item interrupted and thousands of others did not.
            for (match in result.urgentMatches) {
                val pct = ((match.relevanceScore ?: 0.0) * 100).toInt()
                NotificationHelper.showFreeItem(
                    context = applicationContext,
                    title = "Free: ${match.title}",
                    body = listOfNotNull(
                        "${pct}% match",
                        match.locationText,
                    ).joinToString(", "),
                    url = match.url,
                    id = match.listingId.hashCode(),
                )
            }

            // A listing under the median of the saved search that found it. Says which search and
            // how far under, so the notification carries the reason it was sent.
            for (deal in result.deals) {
                NotificationHelper.showDeal(
                    context = applicationContext,
                    title = "${deal.underMedianPct}% under: ${deal.title}",
                    body = listOfNotNull(
                        "${deal.priceText} in ${deal.searchName}",
                        deal.locationText,
                    ).joinToString(", "),
                    url = deal.url,
                    id = deal.listingId.hashCode(),
                )
            }

            // A listing matching a notification subfilter someone set on a specific saved search.
            // Says the subfilter's own name, since that is the reason it was worth interrupting for.
            for (match in result.subfilterMatches) {
                NotificationHelper.showSubfilterMatch(
                    context = applicationContext,
                    title = match.title,
                    body = listOfNotNull(
                        "${match.subfilterName} · ${match.searchName}",
                        match.priceText,
                        match.locationText,
                    ).joinToString(", "),
                    url = match.url,
                    id = match.listingId.hashCode(),
                )
            }

            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val WORK_NAME = "free_item_poll"

        /** Ask this often. Called on app start and whenever the interval setting changes. */
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
