package io.github.tieo.arbay.judging

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.BuyerProtection
import io.github.tieo.arbay.model.ChatCosts
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.Shipping
import io.github.tieo.arbay.model.ShippingOption
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * What buying through Kleinanzeigen costs on top of the price, as Kleinanzeigen itself states it,
 * read by Neuland.
 *
 * Three statements, read again every twelve hours and each kept on disk with the time it was read,
 * so one that does not answer leaves the last reading in place rather than a guess:
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

    @Serializable private data class Stated(val options: List<StatedOption> = emptyList(), val door: List<DoorPrice> = emptyList(), val protection: StatedFee? = null)
    @Serializable private data class StatedOption(
        val id: String, val name: String,
        @kotlinx.serialization.SerialName("carrier_id") val carrierId: String = "",
        @kotlinx.serialization.SerialName("carrier_name") val carrierName: String,
        @kotlinx.serialization.SerialName("package_size") val packageSize: String,
        val cents: Long,
        @kotlinx.serialization.SerialName("from_price") val fromPrice: Boolean = false,
    )
    @Serializable private data class StatedFee(@kotlinx.serialization.SerialName("fixed_cents") val fixedCents: Long, val share: Double)

    /** The fee and when it was read, for the apps and the drafts. */
    suspend fun costs(): ChatCosts {
        val r = readings()
        return ChatCosts(protection = r.protection, protectionReadAt = r.protectionAt)
    }

    /**
     * [shipping] with what delivering it to the door costs, when its stated price is a parcel shop
     * price. The ad's own list at payment, read through the signed-in browser ([liveDoorOptions]),
     * comes first: every way it lists goes to the door, and its prices are what the buyer pays
     * (Hermes Päckchen 5,19 € there against 4,95 € in the help article's table, measured). The
     * help article's table counts where that list cannot be read.
     */
    suspend fun withDoorDelivery(shipping: Shipping?, liveDoorOptions: (suspend () -> List<ShippingOption>?)? = null): Shipping? {
        val cost = shipping?.cost ?: return shipping
        if (shipping.available && !shipping.free) {
            liveDoorOptions?.invoke()?.minByOrNull { it.eur }?.let { cheapest ->
                return shipping.copy(doorCost = Money(Math.round(cheapest.eur * 100), Currency.EUR), doorBy = cheapest.name)
            }
        }
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

    private suspend fun readings(): Readings = lock.withLock {
        var r = state ?: runCatching { json.decodeFromString(Readings.serializer(), file.readText()) }.getOrNull() ?: Readings()
        val now = Clock.System.now()
        fun stale(at: Instant?) = at == null || now - at > 12.hours
        if (stale(r.optionsAt) || stale(r.doorAt) || stale(r.protectionAt)) stated()?.let { read ->
            read.options.takeIf { it.isNotEmpty() }?.let { o -> r = r.copy(options = o.map { Option(it.id, it.name, it.carrierId, it.carrierName, it.packageSize, it.cents, it.fromPrice) }, optionsAt = now) }
            read.door.takeIf { it.isNotEmpty() }?.let { r = r.copy(door = it, doorAt = now) }
            read.protection?.let { r = r.copy(protection = BuyerProtection(fixedEur = it.fixedCents / 100.0, share = it.share), protectionAt = now) }
        }
        if (r != state) runCatching { file.parentFile.mkdirs(); file.writeText(json.encodeToString(Readings.serializer(), r)) }
        state = r
        r
    }

    /** What Kleinanzeigen states now, by Neuland; null where Neuland did not answer. */
    private suspend fun stated(): Stated? = try {
        json.decodeFromJsonElement(Stated.serializer(), io.github.tieo.arbay.neuland.Neuland.request(io.ktor.http.HttpMethod.Get, "costs?site=kleinanzeigen.de", limitS = 60))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Kleinanzeigen's costs unread: {}", e.message)
        null
    }
}
