package io.github.tieo.arbay.crawler

import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
    private val dir = File(System.getProperty("user.home"), ".arbay/geonames")

    // "CC:zip" and "CC:city" → (lat, lon). Built once, lazily.
    private val index: Map<String, Pair<Double, Double>> by lazy { buildIndex() }

    val isAvailable: Boolean get() = index.isNotEmpty()

    private fun buildIndex(): Map<String, Pair<Double, Double>> {
        val map = HashMap<String, Pair<Double, Double>>()
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
                c[2].trim().lowercase().takeIf { it.isNotEmpty() }?.let { map.putIfAbsent("$country:$it", lat to lon) }
            }
        }
        log.info("Geocoder indexed {} postal/place keys across {} countries", map.size, COUNTRIES.size)
        return map
    }

    private fun ensureCountry(cc: String): String? {
        val f = File(dir, "$cc.txt")
        if (f.exists() && f.length() > 0) return f.readText()
        val bytes = URL("https://download.geonames.org/export/zip/$cc.zip").openStream().use { it.readBytes() }
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "$cc.txt") {
                    val txt = zis.readBytes().toString(Charsets.UTF_8)
                    f.writeText(txt)
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

    /** Coordinates for a listing location, or null if it cannot be resolved. Tries zip then city,
     *  scoped to the normalised country; if the country is unknown, tries the zip across all. */
    fun resolve(country: String?, zip: String?, city: String?): Pair<Double, Double>? {
        val cc = normCountry(country)
        val z = zip?.trim()?.takeIf { it.isNotEmpty() }
        val cty = city?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (cc != null) {
            z?.let { index["$cc:$it"]?.let { p -> return p } }
            cty?.let { index["$cc:$it"]?.let { p -> return p } }
            return null
        }
        // Unknown country: the zip alone is often unambiguous enough for a rough distance.
        z?.let { zz -> COUNTRIES.forEach { c -> index["$c:$zz"]?.let { return it } } }
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
