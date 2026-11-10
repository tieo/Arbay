package io.github.tieo.arbay.judging

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.neuland.Neuland
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * ADAC's car cost tables, read by Neuland from ADAC's published PDFs and kept here: what each new
 * model costs a month over five years and 75,000 km (fixed costs, workshop and tyres, running
 * costs, depreciation), and what used cars fetch at a dealer by model and build year.
 */
object Adac {
    private val log = LoggerFactory.getLogger(Adac::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val file = DataDir.file("adac.json")
    private const val ASK_EVERY_MS = 24L * 3600 * 1000

    @Serializable data class Money(val cents: Long, val currency: String)

    /** A model's monthly costs in euros at ADAC's basis ([Costs.years], [Costs.kmPerYear]). */
    @Serializable
    data class CostRow(
        val make: String,
        val model: String,
        @SerialName("workshop_eur_per_hour") val workshopEurPerHour: Int? = null,
        @SerialName("power_kw") val powerKw: Int? = null,
        @SerialName("base_price") val basePrice: Money? = null,
        val fixed: Int,
        val workshop: Int,
        val running: Int,
        val depreciation: Int,
        val total: Int,
    )

    @Serializable
    data class Costs(
        val edition: String? = null,
        val years: Int = 5,
        @SerialName("km_per_year") val kmPerYear: Int = 15_000,
        @SerialName("energy_prices") val energyPrices: Map<String, Double> = emptyMap(),
        val rows: List<CostRow> = emptyList(),
    )

    @Serializable data class YearPrice(val year: Int, val price: Money)

    /** A used model's dealer selling price by build year, beside its price new. */
    @Serializable
    data class UsedRow(
        val make: String,
        val model: String,
        @SerialName("power_kw") val powerKw: Int? = null,
        @SerialName("new_price") val newPrice: Money? = null,
        val electric: Boolean = false,
        val prices: List<YearPrice> = emptyList(),
    )

    @Serializable data class Used(val edition: String? = null, val rows: List<UsedRow> = emptyList())

    /** One current version from ADAC's Autokatalog: its monthly costs on the same basis as the
     *  tables, and its size, battery and charging. */
    @Serializable
    data class CatalogVersion(
        val name: String,
        val from: String? = null,
        val to: String? = null,
        val fuel: String? = null,
        @SerialName("base_price") val basePrice: Money? = null,
        @SerialName("power_kw") val powerKw: Int? = null,
        val fixed: Int? = null,
        val workshop: Int? = null,
        val running: Int? = null,
        val depreciation: Int? = null,
        val total: Int? = null,
        @SerialName("consumption_kwh_per_100km") val consumptionKwhPer100Km: Double? = null,
        @SerialName("range_km") val rangeKm: Int? = null,
        @SerialName("battery_gross_kwh") val batteryGrossKwh: Double? = null,
        @SerialName("battery_net_kwh") val batteryNetKwh: Double? = null,
        val charging: String? = null,
        @SerialName("length_mm") val lengthMm: Int? = null,
        @SerialName("boot_l") val bootL: Int? = null,
        @SerialName("heat_pump") val heatPump: String? = null,
    ) {
        /** The same costs as a table row, for a model the table leaves out. */
        fun asRow(): CostRow? = CostRow(
            make = name.substringBefore(' '), model = name.substringAfter(' '), powerKw = powerKw, basePrice = basePrice,
            fixed = fixed ?: return null, workshop = workshop ?: return null, running = running ?: return null,
            depreciation = depreciation ?: return null, total = total ?: return null,
        )

        /** The most DC power the car charges at, from "AC:11 DC:135". */
        val dcKw: Double? get() = charging?.let { Regex("""DC:\s*(\d+(?:[.,]\d+)?)""").find(it)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() }
    }

    @Serializable
    data class Catalog(val readAt: String? = null, val complete: Boolean = false, val versions: List<CatalogVersion> = emptyList())

    @Serializable
    data class Tables(val readAt: String? = null, val costs: Costs = Costs(), val used: Used = Used(), val catalog: Catalog = Catalog())

    @Volatile var tables: Tables = file.readStore(log) { json.decodeFromString<Tables>(it) } ?: Tables()
        private set

    private val background = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Keeps the tables fresh for as long as the server runs; ADAC publishes twice a year. */
    fun start() {
        background.launch {
            while (true) {
                try {
                    val read = json.decodeFromString<Tables>(Neuland.offers("vehicles/adac", limitS = 60))
                    if (read.costs.rows.isNotEmpty()) {
                        tables = read
                        file.writeTextAtomically(json.encodeToString(Tables.serializer(), read))
                        log.info("ADAC: {} cost rows ({}), {} used rows", read.costs.rows.size, read.costs.edition, read.used.rows.size)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("ADAC tables not read: {}", e.message)
                }
                delay(if (tables.costs.rows.isEmpty()) 3600_000L else ASK_EVERY_MS)
            }
        }
    }

    /** The rows by the make and model the car sites know them as, built once per read of the tables. */
    private class Index(
        val of: Tables,
        val costs: Map<EvModels.ModelKey, List<CostRow>>,
        val used: Map<EvModels.ModelKey, List<UsedRow>>,
        val catalog: Map<EvModels.ModelKey, List<CatalogVersion>>,
    )

    @Volatile private var index: Index? = null

    private fun index(): Index {
        val t = tables
        index?.takeIf { it.of === t }?.let { return it }
        return Index(
            t,
            t.costs.rows.mapNotNull { r -> EvModels.keyOf("${r.make} ${r.model}")?.let { it to r } }.groupBy({ it.first }, { it.second }),
            t.used.rows.mapNotNull { r -> EvModels.keyOf("${r.make} ${r.model.substringBefore(" (")}")?.let { it to r } }.groupBy({ it.first }, { it.second }),
            t.catalog.versions.mapNotNull { v -> EvModels.keyOf(v.name)?.let { it to v } }.groupBy({ it.first }, { it.second }),
        ).also { index = it }
    }

    /** An electric row of the same make nearest in base price to [eur], for a model ADAC's table
     *  leaves out: the same maker's workshop rates and a car of that price's insurance class. */
    fun nearestElectricOfMake(title: String, eur: Double): CostRow? {
        val make = EvModels.keyOf(title)?.make ?: return null
        return index().costs.filterKeys { it.make == make && EvModels.isElectric(it) }
            .values.flatten().filter { it.basePrice != null }
            .minByOrNull { kotlin.math.abs(it.basePrice!!.cents / 100.0 - eur) }
    }

    /** The cheapest price new of the model [title] names, from ADAC's tables and catalogue and
     *  ev-database, in euros; null where none knows the model. */
    fun cheapestNewEur(title: String): Double? {
        val key = EvModels.keyOf(title) ?: return null
        val i = index()
        return (i.costs[key].orEmpty().mapNotNull { it.basePrice?.cents } +
            i.catalog[key].orEmpty().mapNotNull { it.basePrice?.cents } +
            EvModels.versionsOf(title).filter { it.current }.mapNotNull { it.priceDe?.cents })
            .filter { it > 0 }.minOrNull()?.div(100.0)
    }

    /** Every model ADAC knows, by the make and model the car sites know. */
    fun keys(): Set<EvModels.ModelKey> = index().let { it.costs.keys + it.catalog.keys }

    /** The power ratings ADAC gives the model's versions. */
    fun powersOf(key: EvModels.ModelKey): List<Int> = index().let { i ->
        i.costs[key].orEmpty().mapNotNull { it.powerKw } + i.catalog[key].orEmpty().mapNotNull { it.powerKw }
    }

    /** The cost rows of the model [listing] is, told by its power (see [EvModels.keyOf]). */
    fun costsOf(listing: io.github.tieo.arbay.model.Listing): List<CostRow> {
        val key = EvModels.keyOf(listing) ?: return emptyList()
        val kw = listing.vehicle?.powerKw
        val rows = index().costs[key] ?: index().catalog[key].orEmpty().mapNotNull { it.asRow() }
        return rows.sortedWith(compareBy<CostRow>(
            { r -> kw?.let { p -> r.powerKw?.let { kotlin.math.abs(it - p) } ?: Int.MAX_VALUE } ?: 0 },
            { it.basePrice?.cents ?: Long.MAX_VALUE },
        ))
    }

    /** The catalogue versions of the model [listing] is, nearest in power first. */
    fun catalogOf(listing: io.github.tieo.arbay.model.Listing): List<CatalogVersion> {
        val key = EvModels.keyOf(listing) ?: return emptyList()
        val kw = listing.vehicle?.powerKw
        return index().catalog[key].orEmpty().sortedBy { c -> kw?.let { p -> c.powerKw?.let { kotlin.math.abs(it - p) } } ?: 0 }
    }


    /** Every model the catalogue gives a length for, with its most common length. */
    fun lengths(): Map<EvModels.ModelKey, Pair<Int, String>> = index().catalog.mapNotNull { (key, versions) ->
        val length = versions.mapNotNull { it.lengthMm }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return@mapNotNull null
        key to (length to versions.first().name)
    }.toMap()

    /** The used prices of the model [listing] is, every version. */
    fun usedOf(listing: io.github.tieo.arbay.model.Listing): List<UsedRow> = EvModels.keyOf(listing)?.let { index().used[it] }.orEmpty()

    /** The year ADAC's used prices are of: "Herbst/Winter 2026/27" is 2026. */
    val usedYear: Int? get() = tables.used.edition?.let { Regex("""(20\d\d)""").find(it)?.value?.toInt() }
}
