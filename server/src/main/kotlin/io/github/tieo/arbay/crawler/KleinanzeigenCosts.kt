package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.BuyerProtection
import io.github.tieo.arbay.model.ChatCosts
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.Shipping
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory

/**
 * What buying through Kleinanzeigen costs on top of the price, read from Kleinanzeigen itself.
 *
 * Three sources, each read again every twelve hours and kept on disk with the time it was read,
 * so a source that does not answer leaves the last reading in place rather than a guess:
 * - the shipping list the ad form offers sellers (gateway.kleinanzeigen.de shipping-options). An ad
 *   shows only its cheapest shipping, "Versand ab 0,99 €"; the list says which package that price
 *   belongs to and marks it `fromPrice`, the reduced parcel shop to parcel shop price.
 * - the help article on delivery costs, whose Hermes table gives each package's price delivered to
 *   the door, once for a label bought in a shop and once online. The dearer of the two counts, so an
 *   offer stays within the limit either way.
 * - the help article on "Sicher bezahlen", which states the service fee as "0,50 € + 4,5 % des
 *   Kaufpreises".
 */
object KleinanzeigenCosts {
    private val log = LoggerFactory.getLogger(KleinanzeigenCosts::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val OPTIONS_URL = "https://gateway.kleinanzeigen.de/postad/api/v1/shipping-options?posterType=PRIVATE"
    private const val HELP = "https://hilfe.kleinanzeigen.de/api/v2/help_center/de/articles"
    /** "Wie viel kostet die Lieferung und was sind die Lieferzeiten bei Sicher bezahlen?" */
    private const val DELIVERY_ARTICLE = "$HELP/17212925050780.json"
    /** "Was ist Sicher bezahlen & wie funktioniert der Käuferschutz?" */
    private const val PROTECTION_ARTICLE = "$HELP/17211553583388.json"
    private val file get() = DataDir.file("kleinanzeigen_costs.json")
    private val lock = Mutex()
    private var state: Readings? = null

    @Serializable
    data class Option(
        val id: String,
        val name: String,
        val carrierId: String = "",
        val carrierName: String,
        val packageSize: String,
        val priceInEuroCent: Long,
        val fromPrice: Boolean = false,
    )

    /** One package delivered to the door, from the help article's table. */
    @Serializable
    data class DoorPrice(val carrier: String, val product: String, val cents: Long)

    @Serializable
    private data class Readings(
        val options: List<Option> = emptyList(),
        val optionsAt: Instant? = null,
        val door: List<DoorPrice> = emptyList(),
        val doorAt: Instant? = null,
        val protection: BuyerProtection? = null,
        val protectionAt: Instant? = null,
    )

    @Serializable private data class Body(val data: Data)
    @Serializable private data class Data(val shippingOptionsResponse: OptionsResponse)
    @Serializable private data class OptionsResponse(val options: List<Option>)

    /** The fee and when it was read, for the apps and the drafts. */
    suspend fun costs(): ChatCosts {
        val r = readings()
        return ChatCosts(protection = r.protection, protectionReadAt = r.protectionAt)
    }

    /** [shipping] with what delivering it to the door costs, when its stated price is a parcel shop price. */
    suspend fun withDoorDelivery(shipping: Shipping?): Shipping? {
        val cost = shipping?.cost ?: return shipping
        val r = readings()
        val (by, cents) = doorDelivery(cost, r.options, r.door) ?: return shipping
        return shipping.copy(doorCost = Money(cents, Currency.EUR), doorBy = by)
    }

    /**
     * The package whose parcel shop "from" price is [stated], delivered to the door: its own door
     * price from the help article, or else the cheapest fixed price of the same size in the list.
     * Null when [stated] is no such price, as with a seller's own shipping price.
     */
    fun doorDelivery(stated: Money, options: List<Option>, door: List<DoorPrice>): Pair<String, Long>? {
        if (stated.currency != Currency.EUR) return null
        val packages = options.filter { it.fromPrice && it.priceInEuroCent == stated.amount }
        if (packages.isEmpty()) return null
        packages.mapNotNull { p -> door.firstOrNull { it.carrier.equals(p.carrierName, true) && it.product.equals(p.name, true) } }
            .maxByOrNull { it.cents }
            ?.let { return "${it.carrier} ${it.product}" to it.cents }
        val sizes = packages.map { it.packageSize }.toSet()
        return options.filter { !it.fromPrice && it.packageSize in sizes }.minByOrNull { it.priceInEuroCent }
            ?.let { "${it.carrierName} ${it.name}" to it.priceInEuroCent }
    }

    fun parseOptions(body: String): List<Option> = json.decodeFromString(Body.serializer(), body).data.shippingOptionsResponse.options

    /** The help article's article body, from its JSON. */
    private fun articleBody(body: String): String = json.parseToJsonElement(body).jsonObject["article"]!!.jsonObject["body"]!!.jsonPrimitive.content

    /**
     * Each package's door delivery price from the delivery article's tables, read by their headers:
     * the columns under "Haustür-Zustellung", the dearer of them per row.
     */
    fun parseDoorPrices(articleJson: String): List<DoorPrice> {
        val doc = Jsoup.parse(articleBody(articleJson))
        val out = mutableListOf<DoorPrice>()
        // Each table belongs to the heading before it, "Hermes: Preisübersicht ...".
        var heading = ""
        for (element in doc.select("h1, h2, h3, h4, table")) {
            if (element.tagName() != "table") { heading = element.text(); continue }
            val table = element
            val carrier = Regex("""^(\S+?):""").find(heading)?.groupValues?.get(1) ?: continue
            val rows = table.select("tr")
            if (rows.isEmpty()) continue
            // Header columns, with a cell spanning several columns standing over each of them.
            val header = mutableListOf<String>()
            for (cell in rows[0].select("td, th")) repeat(cell.attr("colspan").toIntOrNull() ?: 1) { header += cell.text() }
            val doorColumns = header.indices.filter { header[it].contains("Haustür", ignoreCase = true) }
            if (doorColumns.isEmpty()) continue
            for (row in rows.drop(1)) {
                val cells = row.select("td, th").map { it.text() }
                if (cells.size < header.size) continue
                val cents = doorColumns.mapNotNull { i -> Money.parse(cells[i])?.takeIf { it.currency == Currency.EUR }?.amount }
                if (cents.isNotEmpty()) out += DoorPrice(carrier, cells[0].trim(), cents.max())
            }
        }
        return out
    }

    /** The "Sicher bezahlen" service fee as the article states it: "Servicegebühr von 0,50 € + 4,5 % des Kaufpreises". */
    fun parseProtection(articleJson: String): BuyerProtection? {
        val text = Jsoup.parse(articleBody(articleJson)).text()
        val m = Regex("""Servicegebühr von\s*([\d.,]+)\s*€\s*\+\s*([\d.,]+)\s*%""").find(text) ?: return null
        val fixed = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
        val percent = m.groupValues[2].replace(",", ".").toDoubleOrNull() ?: return null
        return BuyerProtection(fixedEur = fixed, share = percent / 100)
    }

    private suspend fun readings(): Readings = lock.withLock {
        var r = state ?: runCatching { json.decodeFromString(Readings.serializer(), file.readText()) }.getOrNull() ?: Readings()
        val now = Clock.System.now()
        fun stale(at: Instant?) = at == null || now - at > 12.hours
        if (stale(r.optionsAt)) read(OPTIONS_URL, ::parseOptions)?.takeIf { it.isNotEmpty() }?.let { r = r.copy(options = it, optionsAt = now) }
        if (stale(r.doorAt)) read(DELIVERY_ARTICLE, ::parseDoorPrices)?.takeIf { it.isNotEmpty() }?.let { r = r.copy(door = it, doorAt = now) }
        if (stale(r.protectionAt)) read(PROTECTION_ARTICLE, ::parseProtection)?.let { r = r.copy(protection = it, protectionAt = now) }
        if (r != state) runCatching { file.parentFile.mkdirs(); file.writeText(json.encodeToString(Readings.serializer(), r)) }
        state = r
        r
    }

    private suspend fun <T> read(url: String, parse: (String) -> T): T? = try {
        val response = CrawlerRegistry.httpClient.get(url) {
            header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
            header("Accept", "application/json")
        }
        if (response.status.isSuccess()) parse(response.bodyAsText()).also { if (it == null) log.warn("Nothing read from {}", url) }
        else null.also { log.warn("{} answered {}", url, response.status) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Could not read {}: {}", url, e.message)
        null
    }
}
