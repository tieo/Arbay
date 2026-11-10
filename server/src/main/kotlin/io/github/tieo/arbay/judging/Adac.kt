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

    @Serializable
    data class Tables(val readAt: String? = null, val costs: Costs = Costs(), val used: Used = Used())

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
    private class Index(val of: Tables, val costs: Map<EvModels.ModelKey, List<CostRow>>, val used: Map<EvModels.ModelKey, List<UsedRow>>)

    @Volatile private var index: Index? = null

    private fun index(): Index {
        val t = tables
        index?.takeIf { it.of === t }?.let { return it }
        return Index(
            t,
            t.costs.rows.mapNotNull { r -> EvModels.keyOf("${r.make} ${r.model}")?.let { it to r } }.groupBy({ it.first }, { it.second }),
            t.used.rows.mapNotNull { r -> EvModels.keyOf("${r.make} ${r.model.substringBefore(" (")}")?.let { it to r } }.groupBy({ it.first }, { it.second }),
        ).also { index = it }
    }

    /** The model's cost rows; the version closest in power to [powerKw] first where it is known,
     *  else the cheapest. */
    fun costsOf(title: String, powerKw: Int?): List<CostRow> {
        val key = EvModels.keyOf(title) ?: return emptyList()
        return index().costs[key].orEmpty().sortedWith(compareBy<CostRow>(
            { r -> powerKw?.let { p -> r.powerKw?.let { kotlin.math.abs(it - p) } ?: Int.MAX_VALUE } ?: 0 },
            { it.basePrice?.cents ?: Long.MAX_VALUE },
        ))
    }

    /** An electric row of the same make nearest in base price to [eur], for a model ADAC's table
     *  leaves out: the same maker's workshop rates and a car of that price's insurance class. */
    fun nearestElectricOfMake(title: String, eur: Double): CostRow? {
        val make = EvModels.keyOf(title)?.make ?: return null
        return index().costs.filterKeys { it.make == make && EvModels.isElectric(it) }
            .values.flatten().filter { it.basePrice != null }
            .minByOrNull { kotlin.math.abs(it.basePrice!!.cents / 100.0 - eur) }
    }

    /** The model's used prices, every version. */
    fun usedOf(title: String): List<UsedRow> = EvModels.keyOf(title)?.let { index().used[it] }.orEmpty()

    /** The year ADAC's used prices are of: "Herbst/Winter 2026/27" is 2026. */
    val usedYear: Int? get() = tables.used.edition?.let { Regex("""(20\d\d)""").find(it)?.value?.toInt() }
}
