package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.repo.writeTextAtomically
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.slf4j.LoggerFactory

/**
 * Resolves a listing's postal location to coordinates so a distance to the searcher can be computed.
 * Sites do not ship lat/lon in their search data, so this geocodes zip/city + country against the
 * free GeoNames postal dataset, downloaded per country on first use and cached under ~/.arbay
 * (same self-download pattern as the embedding model). Purely additive: if the dataset is
 * unreachable or a place is unknown, resolution returns null and distance is simply omitted.
 */
object Geocoder {
    private val log = LoggerFactory.getLogger(Geocoder::class.java)

    // Countries the fleet crawls; each is a small GeoNames postal file.
    private val COUNTRIES = listOf(
        "DE", "AT", "CH", "FR", "IT", "ES", "BE", "NL", "LU", "DK", "SE", "NO", "FI", "PL", "PT",
        "LT", "RS",
    )
    private val dir = DataDir.file("geonames")

    // "CC:zip" and "CC:city" → (lat, lon). Built once, lazily.
    // Every postal row as well, for the way back from a point to the place it is in.
    private val loaded: Pair<Map<String, Pair<Double, Double>>, List<Place>> by lazy { buildIndex() }
    private val index: Map<String, Pair<Double, Double>> get() = loaded.first
    private val places: List<Place> get() = loaded.second

    /** A postcode with the place it belongs to, as written in GeoNames. */
    data class Place(val country: String, val zip: String, val name: String, val lat: Double, val lon: Double)

    val isAvailable: Boolean get() = index.isNotEmpty()

    private fun buildIndex(): Pair<Map<String, Pair<Double, Double>>, List<Place>> {
        val map = HashMap<String, Pair<Double, Double>>()
        val placeRows = ArrayList<Place>()
        dir.mkdirs()
        for (cc in COUNTRIES) {
            val txt = try { ensureCountry(cc) } catch (e: Exception) { log.warn("geocode load {} failed: {}", cc, e.message); null }
                ?: continue
            txt.lineSequence().forEach { line ->
                val c = line.split('\t')
                if (c.size < 11) return@forEach
                val country = c[0]
                val lat = c[9].toDoubleOrNull() ?: return@forEach
                val lon = c[10].toDoubleOrNull() ?: return@forEach
                c[1].trim().takeIf { it.isNotEmpty() }?.let { map.putIfAbsent("$country:$it", lat to lon) }
                // Germany gives courts, authorities and companies postcodes of their own, listed
                // under their names with a numeric state code and no accuracy; real places carry
                // the state's letters ("BW"). Austria and Switzerland have no such rows.
                val organisation = c.getOrNull(11).isNullOrBlank() && c[4].isNotEmpty() && c[4].all { it.isDigit() }
                if (!organisation && c[1].isNotBlank() && c[2].isNotBlank()) {
                    placeRows.add(Place(country, c[1].trim(), c[2].trim(), lat, lon))
                }
                c[2].trim().lowercase().takeIf { it.isNotEmpty() }?.let { name ->
                    (listOf(name) + listOfNotNull(bracketForm(name))).forEach { map.putIfAbsent("$country:$it", lat to lon) }
                }
            }
        }
        log.info("Geocoder indexed {} postal/place keys across {} countries", map.size, COUNTRIES.size)
        return map to placeRows
    }

    /**
     * The official German short form of a place GeoNames writes out: "Mühldorf am Inn" is signed
     * and typed "Mühldorf (Inn)", "Weil am Rhein" as "Weil (Rhein)". Dropping the river instead
     * would land on whichever other town of that name comes first. Null when the name has no such
     * part.
     */
    internal fun bracketForm(name: String): String? =
        Regex("""^(.+?) (?:an der|an dem|am|an|in der|im|in|ob der|auf der|bei) (.+)$""")
            .matchEntire(name)?.let { "${it.groupValues[1]} (${it.groupValues[2]})" }

    private fun ensureCountry(cc: String): String? {
        val f = File(dir, "$cc.txt")
        if (f.exists() && f.length() > 0) return f.readText()
        val connection = java.net.URI("https://download.geonames.org/export/zip/$cc.zip").toURL().openConnection()
        val bytes = connection.getInputStream().use { it.readBytes() }
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "$cc.txt") {
                    val txt = zis.readBytes().toString(Charsets.UTF_8)
                    f.writeTextAtomically(txt)
                    return txt
                }
                entry = zis.nextEntry
            }
        }
        return null
    }

    /**
     * The postcode nearest to a point, in one country.
     *
     * A site that filters by distance wants what its own sellers type: AutoScout24 takes a German
     * postcode and a radius, not a pair of coordinates. Someone searching "near Rottweil" has named
     * a town, so the town becomes a point and the point becomes the postcode the site understands.
     */
    fun nearestZip(country: String, lat: Double, lon: Double): String? {
        val cc = normCountry(country) ?: return null
        val prefix = "$cc:"
        var best: String? = null
        var bestKm = Double.MAX_VALUE
        index.forEach { (key, coords) ->
            if (!key.startsWith(prefix)) return@forEach
            val value = key.removePrefix(prefix)
            // Only the postal keys: a place name is not what the site's field takes.
            if (!value.all { it.isDigit() || it == ' ' || it == '-' }) return@forEach
            val km = haversine(lat, lon, coords.first, coords.second)
            if (km < bestKm) { bestKm = km; best = value }
        }
        return best
    }

    /** The postal place nearest to a point, across every country indexed: where someone is, in
     *  the words they would type it. Null when nothing is indexed. */
    fun nearestPlace(lat: Double, lon: Double): Place? =
        places.minByOrNull { haversine(lat, lon, it.lat, it.lon) }

    /** Coordinates for a listing location, or null if it cannot be resolved. Tries zip then city,
     *  scoped to the normalised country; if the country is unknown, tries the zip and then the city
     *  across all, home markets first. */
    fun resolve(country: String?, zip: String?, city: String?): Pair<Double, Double>? {
        val cc = normCountry(country)
        val z = zip?.trim()?.takeIf { it.isNotEmpty() }
        val cty = city?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (cc != null) {
            z?.let { index["$cc:$it"]?.let { p -> return p } }
            cty?.let { index["$cc:$it"]?.let { p -> return p } }
            return null
        }
        // Unknown country: the zip alone is often unambiguous enough for a rough distance, and a
        // place name is taken from the first country, home markets first, that has one by that name.
        z?.let { zz -> COUNTRIES.forEach { c -> index["$c:$zz"]?.let { return it } } }
        cty?.let { name -> COUNTRIES.forEach { c -> index["$c:$name"]?.let { return it } } }
        return null
    }

    /** Great-circle distance in km between two coordinates. */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a))
    }

    /** Map the many country forms crawlers emit (ISO2, AutoScout24's single letters, names) to the
     *  ISO2 code GeoNames uses. Null when unrecognised. */
    private fun normCountry(raw: String?): String? {
        val s = raw?.trim()?.uppercase() ?: return null
        return when (s) {
            "DE", "D", "DEUTSCHLAND", "GERMANY" -> "DE"
            "AT", "A", "ÖSTERREICH", "OESTERREICH", "AUSTRIA" -> "AT"
            "CH", "SCHWEIZ", "SWITZERLAND", "SUISSE", "SVIZZERA" -> "CH"
            "FR", "F", "FRANCE", "FRANKREICH" -> "FR"
            "IT", "I", "ITALIA", "ITALY", "ITALIEN" -> "IT"
            "ES", "E", "ESPAÑA", "ESPANA", "SPAIN", "SPANIEN" -> "ES"
            "BE", "B", "BELGIUM", "BELGIË", "BELGIEN", "BELGIQUE" -> "BE"
            "NL", "NIEDERLANDE", "NETHERLANDS", "NEDERLAND", "HOLLAND" -> "NL"
            "LU", "L", "LUXEMBOURG", "LUXEMBURG" -> "LU"
            "DK", "DENMARK", "DÄNEMARK", "DANMARK" -> "DK"
            "SE", "S", "SWEDEN", "SCHWEDEN", "SVERIGE" -> "SE"
            "NO", "N", "NORWAY", "NORWEGEN", "NORGE" -> "NO"
            "FI", "FIN", "FINLAND", "FINNLAND", "SUOMI" -> "FI"
            "PL", "POLAND", "POLEN", "POLSKA" -> "PL"
            "PT", "P", "PORTUGAL" -> "PT"
            "LT", "LITHUANIA", "LITAUEN", "LIETUVA" -> "LT"
            "RS", "SERBIA", "SERBIEN", "SRBIJA" -> "RS"
            else -> null
        }
    }
}
