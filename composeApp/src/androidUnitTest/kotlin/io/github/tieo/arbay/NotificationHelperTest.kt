package io.github.tieo.arbay

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a saved-search alert actually looks like when it is posted.
 *
 * Written after reading the phone's own record of them: three arrived at once one morning, two of
 * which Android throttled as "alert violations", none carried an action, and Android had built a
 * group summary of its own because the app had not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationHelperTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @org.junit.Before
    fun grantPostNotifications() {
        // The helper posts nothing without it, exactly as on a phone that was never asked.
        org.robolectric.Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        ).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun postOne(id: Int = 1, url: String? = "https://www.kleinanzeigen.de/s-anzeige/x/1") =
        NotificationHelper.showSubfilterMatch(
            context = context,
            title = "Petzl GriGri Sicherungsgerät",
            body = "under €550 · parkettschleifmaschine, €42",
            url = url,
            id = id,
        )

    @Test
    fun `a match is grouped and leaves the noise to the summary`() {
        postOne()
        val posted = manager.activeNotifications.map { it.notification }
        val child = posted.first { !it.isGroupSummary() }
        assertEquals(NotificationHelper.CHANNEL_SUBFILTER, child.group)
        assertEquals(
            Notification.GROUP_ALERT_SUMMARY,
            child.groupAlertBehavior,
            "a child that alerts on its own is what made three matches ring three times",
        )
    }

    @Test
    fun `a group always has its summary`() {
        postOne()
        val summary = manager.activeNotifications.map { it.notification }.firstOrNull { it.isGroupSummary() }
        assertNotNull(summary, "without one, Android builds its own")
        assertEquals(NotificationHelper.CHANNEL_SUBFILTER, summary.group)
    }

    @Test
    fun `several matches arrive as one group, not as several arrivals`() {
        postOne(id = 1)
        postOne(id = 2)
        postOne(id = 3)
        val posted = manager.activeNotifications.map { it.notification }
        assertEquals(3, posted.count { !it.isGroupSummary() }, "each listing is still readable")
        assertEquals(1, posted.count { it.isGroupSummary() }, "and they interrupt once")
    }

    @Test
    fun `a listing can be opened from the notification itself`() {
        postOne()
        val child = manager.activeNotifications.map { it.notification }.first { !it.isGroupSummary() }
        assertNotNull(child.contentIntent, "tapping it should reach the listing")
        assertTrue((child.actions?.size ?: 0) >= 1, "and it should say so")
    }

    @Test
    fun `a notification with no link carries no dead button`() {
        postOne(url = null)
        val child = manager.activeNotifications.map { it.notification }.first { !it.isGroupSummary() }
        assertEquals(0, child.actions?.size ?: 0)
    }

    private fun Notification.isGroupSummary() = (flags and Notification.FLAG_GROUP_SUMMARY) != 0
}
