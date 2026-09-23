package io.github.tieo.arbay.repo

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.AuctionReminder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Auctions someone asked to be told about before they end.
 *
 * An auction is the one listing whose price is not what it costs and whose availability has a
 * deadline, so the useful moment to hear about it is a chosen time before that deadline rather
 * than when it was found. Kept on the server because the deadline passes whether or not the app is
 * open, and drained once due: an auction that has ended is not news.
 */
object AuctionReminderStore {
    private val log = LoggerFactory.getLogger(AuctionReminderStore::class.java)
    private val file = DataDir.file("auction_reminders.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private val reminders = ConcurrentHashMap<String, AuctionReminder>()

    init {
        file.readStore(log) { json.decodeFromString<List<AuctionReminder>>(it) }
            ?.forEach { reminders[it.listingId] = it }
    }

    fun all(): List<AuctionReminder> = reminders.values.sortedBy { it.endsAt }

    fun set(reminder: AuctionReminder): AuctionReminder {
        reminders[reminder.listingId] = reminder
        persist()
        return reminder
    }

    fun remove(listingId: String) {
        if (reminders.remove(listingId) != null) persist()
    }

    /**
     * The reminders whose moment has come, taken off the list as they are handed over.
     *
     * One that was missed while the phone was off is still handed over as long as the auction is
     * running: the point is to arrive in time to bid, and late is only useless once it has ended.
     */
    fun drainDue(now: Instant = Clock.System.now()): List<AuctionReminder> {
        // Each reminder is claimed by removing it, so two polls arriving together cannot both hand
        // the same one over and raise the notification twice.
        val due = reminders.values
            .filter { now >= it.endsAt.minus(it.leadMinutes.minutes) }
            .filter { reminders.remove(it.listingId, it) }
        if (due.isEmpty()) return emptyList()
        persist()
        return due.filter { it.endsAt > now }.sortedBy { it.endsAt }
    }

    // Copy and write under one lock, so an older copy never lands after a newer one.
    private fun persist() = synchronized(file) {
        runCatching { file.writeTextAtomically(json.encodeToString(reminders.values.toList())) }
            .onFailure { log.error("Could not save auction reminders: {}", it.message) }
    }
}
