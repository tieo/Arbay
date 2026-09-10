package io.github.tieo.arbay.repo

import io.github.tieo.arbay.model.AuctionReminder
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

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
    private val file = File(System.getProperty("user.home"), ".arbay/auction_reminders.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    private val reminders = ConcurrentHashMap<String, AuctionReminder>()

    init {
        runCatching {
            if (file.exists()) {
                json.decodeFromString<List<AuctionReminder>>(file.readText())
                    .forEach { reminders[it.listingId] = it }
            }
        }.onFailure { log.warn("Could not read auction reminders: {}", it.message) }
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
        val due = reminders.values.filter { now >= it.endsAt.minus(it.leadMinutes.minutes) }
        if (due.isEmpty()) return emptyList()
        due.forEach { reminders.remove(it.listingId) }
        persist()
        return due.filter { it.endsAt > now }.sortedBy { it.endsAt }
    }

    private fun persist() {
        runCatching { file.writeTextAtomically(json.encodeToString(reminders.values.toList())) }
            .onFailure { log.warn("Could not save auction reminders: {}", it.message) }
    }
}
