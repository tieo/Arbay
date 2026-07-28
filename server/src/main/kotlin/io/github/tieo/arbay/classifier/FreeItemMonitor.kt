package io.github.tieo.arbay.classifier

import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.model.NewMatch
import io.github.tieo.arbay.model.NotificationSettings
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.PollResult
import io.github.tieo.arbay.model.SearchQuery
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Background monitor that periodically crawls for new free items
 * and detects high-confidence matches based on the user's profile.
 *
 * Configurable via NotificationSettings (poll interval, thresholds).
 */
object FreeItemMonitor {

    private val log = LoggerFactory.getLogger(FreeItemMonitor::class.java)
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Pending new matches not yet fetched by the client
    private val pendingMatches = mutableListOf<TrackedItem>()
    private var lastRunTime: Instant? = null
    private var lastPollResult: PollResult? = null

    // Notification settings — persisted to disk
    private val settingsFile = File(System.getProperty("user.home"), ".arbay/notification_settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var _settings = loadSettings()

    val settings: NotificationSettings get() = _settings

    fun updateSettings(newSettings: NotificationSettings) {
        _settings = newSettings
        saveSettings()
        // Restart with new interval if running
        if (job?.isActive == true) {
            stop()
            start()
        }
    }

    fun start() {
        if (job?.isActive == true) return
        val intervalMs = _settings.checkEveryMinutes * 60 * 1000L
        log.info("Starting free item background monitor (every ${_settings.checkEveryMinutes}min)")
        job = scope.launch {
            while (isActive) {
                try {
                    runCheck()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Monitor check failed: ${e.message}")
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        log.info("Stopping free item background monitor")
        job?.cancel()
        job = null
    }

    val isRunning: Boolean get() = job?.isActive == true

    /** Fetch and clear pending new matches. */
    fun consumeMatches(): List<TrackedItem> {
        synchronized(pendingMatches) {
            val result = pendingMatches.toList()
            pendingMatches.clear()
            return result
        }
    }

    fun pendingMatchCount(): Int = synchronized(pendingMatches) { pendingMatches.size }

    /** Get the last poll result (for client-side notification decisions). */
    fun getLastPollResult(): PollResult? = lastPollResult

    /** Run a poll now and return categorized results. */
    suspend fun pollNow(): PollResult {
        runCheck()
        return lastPollResult ?: PollResult()
    }

    private suspend fun runCheck() {
        val profile = FreeItemProfileStore.get() ?: return
        if (!profile.trackingEnabled) {
            stop()
            return
        }

        val crawler = CrawlerRegistry.crawlerFor(PlatformId.KLEINANZEIGEN) ?: return
        val profileEmbedding = FreeItemProfileStore.getEmbedding()

        val query = SearchQuery(
            text = "",
            platforms = listOf(PlatformId.KLEINANZEIGEN),
            freeOnly = true,
            location = profile.location,
            radiusKm = profile.radiusKm,
            maxPages = 10,
        )

        log.info("Running background check for new free items...")
        val results = try {
            withTimeout(120_000L) { crawler.search(query) }
        } catch (e: Exception) {
            log.warn("Background crawl failed: ${e.message}")
            return
        }

        // Score results using active model
        val context = ModelRegistry.buildContext()
        val activeModel = ModelRegistry.activeModel()
        val scored = results.map { listing ->
            val text = "${listing.title} ${listing.description ?: ""}"
            val embedding = EmbeddingModel.embed(text)
            val score = if (embedding != null && activeModel != null) {
                try { activeModel.score(embedding, text, context) } catch (_: Exception) { 0.0 }
            } else {
                FreeItemScorer.score(listing, profileEmbedding, profile.description)
            }
            listing.copy(relevanceScore = score)
        }

        // Track and find new items
        val newIds = FreeItemStore.trackBatch(scored)
        val newScored = scored.filter { it.id in newIds }

        // One kind of item is worth interrupting for: near, free, and a good enough fit that
        // waiting until the app is next opened would lose it. A digest of middling matches and
        // a hunt for items unlike anything seen before were two more ways of being told about
        // things nobody was going to fetch.
        val worthTelling = _settings.freeItemScorePct / 100.0
        val urgentMatches = if (_settings.freeItemAlerts) {
            newScored.filter { (it.relevanceScore ?: 0.0) >= worthTelling }
                .sortedByDescending { it.relevanceScore }
        } else emptyList()

        fun toNewMatch(l: io.github.tieo.arbay.model.Listing) = NewMatch(
            listingId = l.id,
            title = l.title,
            url = l.url,
            imageUrl = l.imageUrls.firstOrNull { it.startsWith("http") },
            locationText = l.location?.let { loc -> loc.raw ?: listOfNotNull(loc.zip, loc.city).joinToString(" ") },
            description = l.description,
            relevanceScore = l.relevanceScore,
            firstSeen = Clock.System.now(),
        )

        lastPollResult = PollResult(
            totalNew = newIds.size,
            urgentMatches = urgentMatches.map { toNewMatch(it) },
            lastPollTime = Clock.System.now(),
        )

        // Still populate old-style pending matches for backward compat
        val allNotifiable = urgentMatches.distinctBy { it.id }
        if (allNotifiable.isNotEmpty()) {
            log.info("Found {} items worth telling about", allNotifiable.size)
            synchronized(pendingMatches) {
                allNotifiable.forEach { listing ->
                    pendingMatches.add(TrackedItem(
                        listingId = listing.id,
                        title = listing.title,
                        url = listing.url,
                        imageUrl = listing.imageUrls.firstOrNull { it.startsWith("http") },
                        locationText = listing.location?.let { loc ->
                            loc.raw ?: listOfNotNull(loc.zip, loc.city).joinToString(" ")
                        },
                        description = listing.description,
                        relevanceScore = listing.relevanceScore,
                        firstSeen = Clock.System.now(),
                        lastSeen = Clock.System.now(),
                    ))
                }
            }
        }

        lastRunTime = Clock.System.now()
        log.info("Background check complete: {} total, {} new, {} worth telling about",
            results.size, newIds.size, urgentMatches.size)
    }

    private fun loadSettings(): NotificationSettings {
        if (!settingsFile.exists()) return NotificationSettings()
        return try {
            json.decodeFromString(settingsFile.readText())
        } catch (e: Exception) {
            log.warn("Could not load notification settings: ${e.message}")
            NotificationSettings()
        }
    }

    private fun saveSettings() {
        try {
            settingsFile.parentFile.mkdirs()
            settingsFile.writeText(json.encodeToString(_settings))
        } catch (e: Exception) {
            log.warn("Could not save notification settings: ${e.message}")
        }
    }
}
