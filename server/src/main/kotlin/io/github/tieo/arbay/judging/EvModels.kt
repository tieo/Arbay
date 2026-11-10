package io.github.tieo.arbay.judging

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.neuland.Neuland
import io.github.tieo.arbay.relevance.CarQueryResolver
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
import java.text.Normalizer

/**
 * Every electric car model sold in Europe with its size, boot, range, charging and heat pump, as
 * ev-database.org states them, read by Neuland and kept here.
 *
 * It answers what a market's card does not: how big a car is, so a search can ask for "at least
 * as big as an ID.3" across every make, and the range, charging power and heat pump a model has
 * where the ad does not say. A version is placed under the make and model the car sites file it
 * as (the same words resolve the search and the offer's title), so "ID.4 Pro 4MOTION" counts as
 * an ID.4 and "Model Y Long Range" as a Model Y.
 */
object EvModels {
    private val log = LoggerFactory.getLogger(EvModels::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val file = DataDir.file("ev_models.json")

    /** Neuland reads the source again monthly; asking daily picks a finished read up within a day. */
    private const val ASK_EVERY_MS = 24L * 3600 * 1000

    /** A first read still filling in comes in again sooner, so its sizes arrive within the hour. */
    private const val ASK_WHILE_READING_MS = 3600L * 1000

    @Serializable
    data class Money(val cents: Long, val currency: String)

    /** One version of a model, its figures as the source states them; null where it states none. */
    @Serializable
    data class Version(
        val make: String,
        val model: String,
        val current: Boolean = false,
        val body: String? = null,
        val seats: Int? = null,
        @SerialName("length_mm") val lengthMm: Int? = null,
        @SerialName("boot_l") val bootL: Int? = null,
        @SerialName("battery_kwh") val batteryKwh: Double? = null,
        @SerialName("battery_usable_kwh") val batteryUsableKwh: Double? = null,
        @SerialName("power_kw") val powerKw: Int? = null,
        @SerialName("wltp_range_km") val wltpRangeKm: Int? = null,
        @SerialName("dc_max_kw") val dcMaxKw: Double? = null,
        @SerialName("heat_pump") val heatPump: Boolean? = null,
        @SerialName("heat_pump_standard") val heatPumpStandard: Boolean? = null,
        @SerialName("price_de") val priceDe: Money? = null,
        val url: String? = null,
    )

    @Serializable
    data class Catalogue(
        val source: String? = null,
        val readAt: String? = null,
        val complete: Boolean = false,
        val reading: Boolean = false,
        val models: List<Version> = emptyList(),
    )

    /** A model as the car sites file it: the make's slug and the model's name in folded letters. */
    data class ModelKey(val make: String, val model: String)

    @Volatile private var catalogue: Catalogue = file.readStore(log) { json.decodeFromString<Catalogue>(it) } ?: Catalogue()
    @Volatile private var grouped: Map<ModelKey, List<Version>> = group(catalogue)

    private val background = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Keeps the catalogue fresh for as long as the server runs. */
    fun start() {
        background.launch {
            while (true) {
                try {
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("EV models not read: {}", e.message)
                }
                delay(if (catalogue.complete) ASK_EVERY_MS else ASK_WHILE_READING_MS)
            }
        }
    }

    private suspend fun refresh() {
        val read = json.decodeFromString<Catalogue>(Neuland.offers("vehicles/models/electric", limitS = 60))
        if (read.models.isEmpty()) return
        catalogue = read
        grouped = group(read)
        file.writeTextAtomically(json.encodeToString(Catalogue.serializer(), read))
        log.info("EV models: {} versions, {} with their size, in {} models{}", read.models.size,
            read.models.count { it.lengthMm != null }, grouped.size, if (read.complete) "" else " (still being read)")
    }

    private fun group(c: Catalogue): Map<ModelKey, List<Version>> =
        c.models.mapNotNull { v -> keyOf("${v.make} ${v.model}")?.let { it to v } }.groupBy({ it.first }, { it.second })

    /** The model [text] names, by the make and model the car sites know, or null where it names none. */
    fun keyOf(text: String): ModelKey? {
        val car = CarQueryResolver.resolve(plain(text)) ?: return null
        val model = car.modelSlug ?: return null
        return ModelKey(car.makeSlug, fold(model))
    }

    /**
     * The model [listing] is, told by its power where its title names a shorter name than the
     * model: "BYD Dolphin 65 kW Active" is a Dolphin Surf, the one Dolphin with 65 kW. The title's
     * own model stands where one of its versions has that power, or where the power is unknown.
     */
    fun keyOf(listing: Listing): ModelKey? {
        val base = keyOf(listing.title) ?: return null
        val kw = listing.vehicle?.powerKw ?: return base
        fun hasPower(key: ModelKey) = (grouped[key].orEmpty().mapNotNull { it.powerKw } + Adac.powersOf(key)).any { kotlin.math.abs(it - kw) <= 2 }
        if (hasPower(base)) return base
        val siblings = (grouped.keys + Adac.keys()).filter { it.make == base.make && it.model != base.model && it.model.startsWith(base.model) }
        return siblings.sortedBy { it.model.length }.firstOrNull(::hasPower) ?: base
    }

    /** The length of the model [listing] is, told by its power where its title is short of it. */
    fun lengthOf(listing: Listing): Int? = keyOf(listing)?.let { key ->
        lengthOf(grouped[key].orEmpty()) ?: Adac.lengths()[key]?.first
    }

    /** Letters without accents ("Citroën ë-C3" reads "Citroen e-C3"), as the sites' own names are written. */
    private fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun fold(s: String) = plain(s).lowercase().filter { it.isLetterOrDigit() }

    /** Whether the catalogue knows [key] as an electric model. */
    fun isElectric(key: ModelKey): Boolean = key in grouped

    /** The versions of the model [text] names; empty where the catalogue has none. */
    fun versionsOf(text: String): List<Version> = keyOf(text)?.let { grouped[it] }.orEmpty()

    /**
     * The version of its model an offer is: the one whose own words the title shares most (its
     * battery, "Pro", "GTX", "Long Range"), a version still sold winning a tie. Null where the
     * catalogue does not know the model.
     */
    fun versionOf(listing: Listing): Version? {
        val versions = versionsOf(listing.title).ifEmpty { return null }
        val words = words(listing.title)
        return versions.maxWithOrNull(compareBy<Version>({ v -> words(v.model).count { it in words } }, { it.current }, { it.lengthMm != null }))
    }

    private fun words(s: String) = plain(s).lowercase().split(Regex("[^a-z0-9.]+")).filter { it.isNotBlank() }.toSet()

    /** A model's length: its versions share the body, so the most common length among them. */
    fun lengthOf(versions: List<Version>): Int? =
        versions.mapNotNull { it.lengthMm }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /** The length of the model [title] names, from ev-database or else ADAC's catalogue. */
    fun lengthOf(title: String): Int? = lengthOf(versionsOf(title))
        ?: keyOf(title)?.let { Adac.lengths()[it]?.first }

    /**
     * Every model still sold at least [minLengthMm] long, by the words a car site is searched
     * with ("Volkswagen ID.4", "Tesla Model Y"), shortest first. A model with no length read yet
     * is left out, since how big it is is not known.
     */
    fun atLeast(minLengthMm: Int): List<String> {
        val fromEvDatabase = grouped.mapNotNull { (key, versions) ->
            val current = versions.filter { it.current }.ifEmpty { return@mapNotNull null }
            val length = lengthOf(current) ?: return@mapNotNull null
            key to (searchWords(key, current.first().make, current.first().model) to length)
        }.toMap()
        // ADAC's catalogue holds only versions on sale, each with its length.
        val fromAdac = Adac.lengths().mapValues { (key, l) -> searchWords(key, l.second.substringBefore(' '), l.second.substringAfter(' ')) to l.first }
        return (fromAdac + fromEvDatabase).values.filter { it.second >= minLengthMm }.sortedBy { it.second }.map { it.first }.distinct()
    }

    /** The words a car site finds the model under: the catalogue's make, and the model as the sites name it. */
    private fun searchWords(key: ModelKey, makeName: String, modelName: String): String {
        val make = io.github.tieo.arbay.relevance.CarTaxonomyProvider.current.makes.firstOrNull { it.id == key.make }
        val model = make?.models?.firstOrNull { fold(it.name) == key.model }?.name
        return listOfNotNull(make?.name ?: makeName, model ?: modelName).joinToString(" ")
    }

    /** The length a search asks at least, by the model it names as its size; null where it names
     *  none or the catalogue has no length for it yet. */
    fun minLengthMm(filters: io.github.tieo.arbay.model.CarFilters?): Int? =
        filters?.atLeastSizeOf?.takeIf { it.isNotBlank() }?.let(::lengthOf)

    fun idealLengthMm(filters: io.github.tieo.arbay.model.CarFilters?): Int? =
        filters?.idealSizeOf?.takeIf { it.isNotBlank() }?.let(::lengthOf)

    /**
     * The search with every model of the size it asks for searched as well: the car sites are
     * asked by make and model, so a search across makes is one search per model. The models the
     * search names itself are not asked twice.
     */
    fun withModelsOfTheSize(query: io.github.tieo.arbay.model.SearchQuery): io.github.tieo.arbay.model.SearchQuery {
        val min = minLengthMm(query.carFilters) ?: return query
        val named = (listOf(query.text) + query.aliases).mapNotNull(::keyOf).toSet()
        val more = atLeast(min).filter { keyOf(it) !in named }
        return if (more.isEmpty()) query else query.copy(aliases = query.aliases + more)
    }

    /** Whether [kw], an ad's power for the car [title] names, is one of its model's battery sizes
     *  in kWh and none of its power ratings: a dealer typed the battery into the power field. */
    fun powerIsABattery(title: String, kw: Int): Boolean {
        val versions = versionsOf(title).ifEmpty { return false }
        val batteries = versions.flatMap { listOfNotNull(it.batteryUsableKwh, it.batteryKwh) }.map { kotlin.math.round(it).toInt() }
        val powers = versions.mapNotNull { it.powerKw }
        return batteries.any { kotlin.math.abs(it - kw) <= 1 } && powers.none { kotlin.math.abs(it - kw) <= 2 }
    }

    /** Whether the catalogue has every version's figures yet; a size read now may still be missing. */
    val complete: Boolean get() = catalogue.complete
}
