package io.github.tieo.arbay.repo

import io.github.tieo.arbay.model.AuctionReminder
import io.github.tieo.arbay.model.SubfilterMatch
import kotlinx.datetime.Clock

/**
 * What has been handed to the phone to raise as notifications, kept until the phone says it
 * raised it.
 *
 * The matches and reminders used to be taken off the server the moment a poll asked for them.
 * A poll whose answer never arrived (the connection dropped, the phone slept mid-request) lost
 * them for good. Each one now carries a number; the phone sends back the highest number it has
 * shown on its next poll, and whatever is above it is sent again. A notification sent twice
 * replaces itself, since its id comes from the listing.
 */
object NotificationOutbox {
    private class Held(val seq: Long, val match: SubfilterMatch? = null, val reminder: AuctionReminder? = null)

    class Delivery(
        val subfilterMatches: List<SubfilterMatch>,
        val auctionReminders: List<AuctionReminder>,
        /** The highest number in this delivery, for the phone to send back once it is shown. */
        val upTo: Long,
    )

    // A phone that never acknowledges must not make this grow for ever; past this the oldest go.
    private const val MAX_HELD = 200

    // Numbers never go back, across restarts included: they start from the clock, so the number
    // a phone stored before a restart is below everything held after it. Counting from 1 again
    // would have marked the new ones as already raised.
    private var nextSeq = 0L
    private val held = ArrayDeque<Held>()

    private fun number(): Long {
        nextSeq = maxOf(nextSeq + 1, System.currentTimeMillis())
        return nextSeq
    }

    @Synchronized
    fun hold(matches: List<SubfilterMatch>, reminders: List<AuctionReminder>) {
        matches.forEach { held.addLast(Held(number(), match = it)) }
        reminders.forEach { held.addLast(Held(number(), reminder = it)) }
        while (held.size > MAX_HELD) held.removeFirst()
    }

    /** Everything the phone has not yet acknowledged, once what it has acknowledged is let go. */
    @Synchronized
    fun afterAcknowledging(acknowledged: Long): Delivery {
        held.removeAll { it.seq <= acknowledged }
        return delivery(held.toList(), fallbackUpTo = acknowledged)
    }

    /** Everything held, handed over and let go: how a phone that does not acknowledge is served. */
    @Synchronized
    fun takeAll(): Delivery {
        val all = held.toList()
        held.clear()
        return delivery(all, fallbackUpTo = 0)
    }

    private fun delivery(items: List<Held>, fallbackUpTo: Long): Delivery {
        val now = Clock.System.now()
        return Delivery(
            subfilterMatches = items.mapNotNull { it.match },
            // An auction that ended while its reminder waited is no longer worth raising.
            auctionReminders = items.mapNotNull { it.reminder }.filter { it.endsAt > now },
            upTo = items.maxOfOrNull { it.seq } ?: fallbackUpTo,
        )
    }
}
