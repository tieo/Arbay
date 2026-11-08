package io.github.tieo.arbay.judging

import io.github.tieo.arbay.chat.AuctionBids
import io.github.tieo.arbay.markets.Crawler
import io.github.tieo.arbay.markets.DetailCache
import io.github.tieo.arbay.markets.RequestMonitor
import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.DriveFacts
import io.github.tieo.arbay.model.DriveHealth
import io.github.tieo.arbay.model.DriveHoursFrom
import io.github.tieo.arbay.model.DriveInterface
import io.github.tieo.arbay.model.DriveVerdict
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ListingDetail
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.landedPrice
import io.github.tieo.arbay.model.looksLikeADrive
import io.github.tieo.arbay.model.looksLikeADriveSearch
import io.github.tieo.arbay.repo.ImportSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes

/**
 * Drive offers with what they state laid over them, read from their card at once and from their page
 * and photos in the background, so a search for a drive can be compared without opening its ads.
 * The photos are read when an offer is found, cheapest per gigabyte first; RapidOCR takes every core,
 * so nothing is read while a bid is near.
 */
object DriveOffers {
    private val log = LoggerFactory.getLogger(DriveOffers::class.java)

    private class Wanted(val listing: Listing, val crawler: Crawler)

    private val waiting = LinkedHashMap<String, Wanted>()
    private val triedAt = HashMap<String, Long>()
    private const val TRY_AGAIN_MS = 6L * 60 * 60 * 1000
    private const val BUDGET_WAIT_MS = 90_000L

    /** [listings] of [query]'s search with their facts, and their pages and photos put in the queue. */
    fun found(listings: List<Listing>, query: SearchQuery, crawler: Crawler): List<Listing> {
        if (!isDriveSearch(query)) return listings
        val attached = listings.map { if (isDrive(it)) attach(it) else it }
        attached.filter { it.drive != null }.forEach { want(it, crawler) }
        return attached
    }

    private fun isDriveSearch(query: SearchQuery) = looksLikeADriveSearch(query.positiveText) || query.aliases.any(::looksLikeADriveSearch)

    /** Whether the offer is for a drive, not the case, caddy or cable a drive search also finds. */
    private fun isDrive(l: Listing) = looksLikeADrive(l.title) || DriveText.capacityOfWords(l.title) != null

    private fun want(l: Listing, crawler: Crawler) {
        if (l.condition == Condition.PARTS_ONLY) return
        val detail = DetailCache.get(l.id)
        val photos = detail?.photos?.ifEmpty { l.imageUrls } ?: emptyList()
        if (detail != null && SmartScreens.known(photos).photosPending == 0) return
        val now = System.currentTimeMillis()
        synchronized(waiting) {
            if ((triedAt[l.id] ?: 0L) + TRY_AGAIN_MS > now) return
            waiting[l.id] = Wanted(l, crawler)
        }
    }

    /** [l] with what is known of it now; free, since it only asks what has been read already. */
    fun attach(l: Listing): Listing {
        val detail = DetailCache.get(l.id)
        val photos = detail?.photos?.ifEmpty { l.imageUrls } ?: emptyList()
        return l.copy(drive = read(l, detail, SmartScreens.known(photos)))
    }

    /** [l] as what its facts say now, for an offer kept from an earlier search. */
    fun current(l: Listing): Listing = if (l.drive != null) attach(l) else l

    internal fun read(l: Listing, detail: ListingDetail?, health: DriveHealth): DriveFacts {
        val pageText = detail?.description.orEmpty()
        val all = l.title + "\n" + (l.description.orEmpty()) + "\n" + pageText
        val inTitle = DriveText.models(l.title)
        // A page can name a drive it is compared with, so its model counts only where it names one.
        val model = inTitle.firstOrNull() ?: DriveText.models(all).singleOrNull()
        val capacity = model?.let(DriveText::capacityOfModel) ?: DriveText.capacityOfWords(l.title)
            ?: DriveText.capacityOfWords(l.description.orEmpty()) ?: DriveText.capacityOfWords(pageText)
        val iface = model?.let(DriveText::interfaceOfModel) ?: DriveText.interfaceOfWords(l.title) ?: DriveText.interfaceOfWords(all)
        val recording = DriveText.recordingOfWords(all) ?: model?.let(DriveText::recordingOfModel)
        val readings = health.readings
        val hoursSeen = readings.mapNotNull { it.powerOnHours }.maxOrNull()
        val wordsHours = DriveText.hoursOfWords(all)
        val isNew = wordsHours == null && hoursSeen == null &&
            (DriveText.saysNew(all) || (l.platformId != PlatformId.KLEINANZEIGEN && l.condition == Condition.NEW))
        val (hours, from) = when {
            hoursSeen != null -> hoursSeen to DriveHoursFrom.PHOTO
            wordsHours != null -> wordsHours to DriveHoursFrom.AD_TEXT
            isNew -> 0L to DriveHoursFrom.NEW
            else -> null to null
        }
        val verdict = listOf(DriveVerdict.FAILING, DriveVerdict.WORN, DriveVerdict.CLEAN, DriveVerdict.UNREADABLE)
            .firstOrNull { v -> readings.any { it.verdict == v } }
        val saysFailing = DriveText.saysFailing(l.title, all) ?: "listed for parts".takeIf { l.condition == Condition.PARTS_ONLY }
        val health2 = if (saysFailing != null) DriveVerdict.FAILING else verdict
        val note = saysFailing ?: readings.firstOrNull { it.verdict == verdict }?.findings?.firstOrNull()
        val unit = unitPrice(l, all)
        val lot = DriveText.lotOf(l.title)
        val stock = DriveText.stockOf(all) ?: detail?.available?.takeIf { it > 1 } ?: lot
        val perPiece = unit != null || DriveText.saysPerPiece(all)
        val unclear = lot != null && !perPiece
        val cents = if (unclear) null else centsPerGb(l, unit, capacity)
        val hdd = iface != DriveInterface.NVME && !Regex("""\bssd\b""", RegexOption.IGNORE_CASE).containsMatchIn(l.title)
        val family = model?.let(DriveLife::familyOf) ?: DriveLife.familyOfLine(l.title)
        val life = if (hdd && health2 != DriveVerdict.FAILING) hours?.let { DriveLife.expected(family, it) } else null
        return DriveFacts(
            capacityGb = capacity, model = model, driveInterface = iface, recording = recording,
            external = DriveText.externalOf(l.title, model), powerOnHours = hours, hoursFrom = from,
            health = health2, healthNote = note, unitPrice = unit, stock = stock, lotPriceUnclear = unclear,
            pageRead = detail != null, photosPending = health.photosPending, centsPerGb = cents,
            lifeHours = life?.hoursLeft, failChance5y = life?.failChance5y, lifeBasis = life?.basis,
        )
    }

    /** One drive's price where the ad sells several: the words' figure where the listed price is
     *  plainly the whole lot's, otherwise the listed price, which is what the market will charge. */
    private fun unitPrice(l: Listing, text: String): Money? {
        val said = DriveText.unitCents(text)?.takeIf { it > 0 } ?: return if (DriveText.saysPerPiece(text)) l.price else null
        return when {
            l.price.currency != Currency.EUR -> null
            l.price.amount >= said * 3 / 2 -> Money(said, Currency.EUR)
            else -> l.price
        }
    }

    private fun centsPerGb(l: Listing, unit: Money?, capacityGb: Int?): Double? {
        if (capacityGb == null || capacityGb <= 0 || l.saleType == SaleType.AUCTION || l.saleType == SaleType.RENT) return null
        val one = if (unit != null) l.copy(price = unit) else l
        val landed = one.landedPrice(ImportSettingsStore.current)
        val cents = (if (landed.currency == Currency.EUR) landed.amount
            else runCatching { ExchangeRates.convert(landed.amount, landed.currency.name, "EUR") }.getOrNull()) ?: return null
        return cents.takeIf { it > 0 }?.let { it.toDouble() / capacityGb }
    }

    /** Reads the pages and photos of what was found, one offer at a time, for as long as the server runs. */
    suspend fun run() {
        while (true) {
            try {
                if (AuctionBids.busySoon(15.minutes)) { delay(60_000); continue }
                val next = synchronized(waiting) {
                    val best = waiting.values.minByOrNull { it.listing.drive?.centsPerGb ?: Double.MAX_VALUE } ?: return@synchronized null
                    waiting.remove(best.listing.id)
                    triedAt[best.listing.id] = System.currentTimeMillis()
                    best
                }
                if (next == null) { delay(5_000); continue }
                readOne(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Reading a drive offer failed: {}", e.message)
                delay(5_000)
            }
        }
    }

    private suspend fun readOne(w: Wanted) {
        val l = w.listing
        val detail = DetailCache.get(l.id) ?: run {
            if (!RequestMonitor.awaitBudget(w.crawler.platformId.displayName, System.currentTimeMillis() + BUDGET_WAIT_MS)) return
            w.crawler.fetchDetail(l)?.also { DetailCache.put(l.id, it) }
        }
        val photos = detail?.photos?.ifEmpty { l.imageUrls } ?: l.imageUrls
        SmartScreens.readAll(l.id, l.title, photos) { PhotoPrints.downloadAt(it) }
    }
}
