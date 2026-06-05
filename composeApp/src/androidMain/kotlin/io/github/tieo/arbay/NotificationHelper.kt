package io.github.tieo.arbay

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {

    // ── Channel IDs (visible in Android notification settings) ────────────
    const val CHANNEL_URGENT = "urgent_matches"
    const val CHANNEL_DIGEST = "match_digest"
    const val CHANNEL_NOVEL = "novel_items"

    private var channelsCreated = false

    /** Create all notification channels. Call once on app start. */
    fun ensureChannels(context: Context) {
        if (channelsCreated) return

        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Urgent matches — high priority, heads-up, sound+vibrate
        mgr.createNotificationChannel(NotificationChannel(
            CHANNEL_URGENT,
            "Urgent Matches",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Instant notifications for very high-confidence free item matches (e.g. >90%)"
            enableVibration(true)
        })

        // Match digest — default priority, periodic summary
        mgr.createNotificationChannel(NotificationChannel(
            CHANNEL_DIGEST,
            "Match Digest",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Periodic summary of new free items above your match threshold"
        })

        // Novel/rare items — medium priority
        mgr.createNotificationChannel(NotificationChannel(
            CHANNEL_NOVEL,
            "Novel Items",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Items unlike anything you've seen before — potentially rare finds"
        })

        channelsCreated = true
    }

    private fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    /** Show an urgent match notification (high priority, heads-up). */
    fun showUrgentMatch(title: String, body: String, url: String? = null) {
        val context = MainActivity.instance ?: return
        ensureChannels(context)
        if (!hasPermission(context)) return

        val builder = NotificationCompat.Builder(context, CHANNEL_URGENT)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)

        url?.let {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
            val pending = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(pending)
        }

        NotificationManagerCompat.from(context).notify(
            System.currentTimeMillis().toInt(), builder.build(),
        )
    }

    /** Show a digest notification summarizing new matches. */
    fun showDigest(count: Int, topTitle: String?) {
        val context = MainActivity.instance ?: return
        ensureChannels(context)
        if (!hasPermission(context)) return

        val body = if (topTitle != null) {
            "$count new items match your interests. Top: $topTitle"
        } else {
            "$count new items match your interests"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_DIGEST)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Free Items Digest")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(
            "digest".hashCode(), notification,
        )
    }

    /** Show a novel item notification. */
    fun showNovelItem(title: String, body: String, url: String? = null) {
        val context = MainActivity.instance ?: return
        ensureChannels(context)
        if (!hasPermission(context)) return

        val builder = NotificationCompat.Builder(context, CHANNEL_NOVEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        url?.let {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
            val pending = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(pending)
        }

        NotificationManagerCompat.from(context).notify(
            System.currentTimeMillis().toInt(), builder.build(),
        )
    }

    /** Legacy method — delegates to urgent. */
    fun showNewMatchNotification(title: String, body: String) {
        showUrgentMatch(title, body)
    }
}
