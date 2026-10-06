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

/**
 * The two notifications the app raises, on the device, one channel each so either can be silenced
 * in Android's own settings.
 *
 * Both are about something that stops existing while you wait: a free item near you, and a listing
 * priced under what its search usually costs. Anything the app knows that will still be there in an
 * hour is shown when the app is opened.
 */
object NotificationHelper {

    /** A free item near you, scoring above the threshold set in the app. */
    const val CHANNEL_FREE_ITEM = "free_item_match"

    /** A listing matching a notification subfilter someone set on a specific saved search. */
    const val CHANNEL_SUBFILTER = "saved_search_alert"

    private var channelsCreated = false

    /** Create the channels. Called on app start and before any notification. */
    fun ensureChannels(context: Context) {
        if (channelsCreated) return

        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        mgr.createNotificationChannel(NotificationChannel(
            CHANNEL_FREE_ITEM,
            "Free item near you",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Someone is giving away something that matches what you look for. " +
                "Free items are usually gone within the hour."
            enableVibration(true)
        })

        mgr.createNotificationChannel(NotificationChannel(
            CHANNEL_SUBFILTER,
            "Saved search alert",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "A listing matched a notification subfilter you set on one of your saved searches."
            enableVibration(true)
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

    private fun show(context: Context, channel: String, id: Int, title: String, body: String, url: String?) {
        ensureChannels(context)
        if (!hasPermission(context)) return

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            // One crawl can match several listings at once, and each one used to arrive on its own
            // with its own sound: three at 09:26 one morning, two of which Android throttled as
            // arriving too fast, and it invented a group of its own to tidy them up. Grouped here
            // instead, and only the summary is allowed to make a noise, so a run that finds five
            // things interrupts once and the five are there to read.
            .setGroup(channel)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)

        url?.let {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
            val pending = PendingIntent.getActivity(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(pending)
            // Tapping the notification already opens the listing; the button says that it will,
            // which is the difference between a notification you act on and one you dismiss.
            builder.addAction(android.R.drawable.ic_menu_view, "Open listing", pending)
        }

        NotificationManagerCompat.from(context).notify(id, builder.build())
        postGroupSummary(context, channel)
    }

    /** The one notification of a group that is allowed to interrupt, and the one the shade shows
     *  when the group is collapsed. Posted after each child so a group always has one. */
    private fun postGroupSummary(context: Context, channel: String) {
        val summary = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(
                when (channel) {
                    CHANNEL_FREE_ITEM -> "Free items near you"
                    else -> "Saved search alerts"
                },
            )
            .setGroup(channel)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(channel.hashCode(), summary)
    }

    /** A free item worth fetching now. */
    fun showFreeItem(context: Context, title: String, body: String, url: String?, id: Int) =
        show(context, CHANNEL_FREE_ITEM, id, title, body, url)

    /** A listing matching a notification subfilter on a specific saved search. */
    fun showSubfilterMatch(context: Context, title: String, body: String, url: String?, id: Int) =
        show(context, CHANNEL_SUBFILTER, id, title, body, url)

    /** The in-app seam for raising the free-item notification from shared code. */
    fun showNewMatchNotification(title: String, body: String) {
        show(ArbayApplication.context, CHANNEL_FREE_ITEM, title.hashCode(), title, body, null)
    }
}
