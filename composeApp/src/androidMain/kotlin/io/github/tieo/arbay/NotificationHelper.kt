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

    /** A listing in a watched search priced under that search's median. */
    const val CHANNEL_DEAL = "under_market_deal"

    private var channelsCreated = false

    /** Create both channels. Called on app start and before any notification. */
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
            CHANNEL_DEAL,
            "Under the usual price",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "A listing in one of your saved searches is priced well under what that " +
                "search usually costs."
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

        url?.let {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
            val pending = PendingIntent.getActivity(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(pending)
        }

        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    /** A free item worth fetching now. */
    fun showFreeItem(context: Context, title: String, body: String, url: String?, id: Int) =
        show(context, CHANNEL_FREE_ITEM, id, title, body, url)

    /** A listing under the median of the search that found it. */
    fun showDeal(context: Context, title: String, body: String, url: String?, id: Int) =
        show(context, CHANNEL_DEAL, id, title, body, url)

    /** The in-app seam for raising the free-item notification from shared code. */
    fun showNewMatchNotification(title: String, body: String) {
        val context = MainActivity.instance ?: return
        show(context, CHANNEL_FREE_ITEM, title.hashCode(), title, body, null)
    }
}
