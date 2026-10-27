package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

/**
 * Parses a marktplaats.nl / 2dehands.be vehicle detail page into a verified VehicleInfo. The page
 * embeds an `attr` object in its hydration state carrying the full spec set the search card omits
 * (power, gearbox, fuel, doors, colour, body type), keyed by the site's Dutch labels. These are the
 * seller's declared attributes, so the values are recorded as verified and may exclude on a filter.
 */
object MarktplaatsDetailParser {

    /**
     * The ad's page as a whole: the seller's text, who sells it and every photo, besides a vehicle's
     * attributes. The search card carries a cut description; "let op: Geen NVMe" stood past the cut.
     */
    fun parseAd(html: String): ListingDetail? {
        val doc = Jsoup.parse(html)
        val description = doc.selectFirst("[data-collapsable=description]")?.let { text ->
            text.select("br").forEach { it.replaceWith(TextNode("\n")) }
            text.wholeText().lines().joinToString("\n") { it.trim() }.trim()
        }?.takeIf { it.isNotEmpty() }
        val listing = config(html)?.get("listing")?.jsonObject
        val seller = listing?.get("seller")?.jsonObject?.let { s ->
            SellerProfile(
                name = s["name"]?.jsonPrimitive?.contentOrNull,
                type = when (s["sellerType"]?.jsonPrimitive?.contentOrNull) {
                    null -> null
                    "CONSUMER" -> SellerType.PRIVATE
                    else -> SellerType.BUSINESS
                },
            )
        }
        val photos = doc.select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
            runCatching { Json.parseToJsonElement(script.data()).jsonObject }.getOrNull()
                ?.takeIf { it["@type"]?.jsonPrimitive?.contentOrNull == "Product" }
                ?.get("image")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.orEmpty()
        val detail = ListingDetail(vehicle = parse(html), description = description, seller = seller, photos = photos)
        return detail.takeUnless { it == ListingDetail() }
    }

    /** The page's hydration state, `window.__CONFIG__ = {...};`. */
    private fun config(html: String): JsonObject? {
        val start = html.indexOf("__CONFIG__ = ").takeIf { it >= 0 } ?: return null
        val body = html.substring(start + "__CONFIG__ = ".length)
        return runCatching { Json.decodeFromString<JsonObject>(body.substring(0, jsonEnd(body))) }.getOrNull()
    }

    /** Where the JSON object at the start of [text] closes, strings and escapes respected. */
    private fun jsonEnd(text: String): Int {
        var depth = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return i + 1
            }
            i++
        }
        return text.length
    }

    fun parse(html: String): VehicleInfo? {
        // Vermogen is quoted in PK (metric horsepower) here, e.g. "142"; 1 PK = 0.7355 kW.
        val powerKw = attr(html, "Vermogen")?.filter { it.isDigit() }?.toIntOrNull()
            ?.let(::psToKw)?.takeIf { it in 20..1000 }

        val info = VehicleInfo(
            firstRegYear = attr(html, "Bouwjaar")?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 1900..2100 },
            mileageKm = attr(html, "Kilometerstand")?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 1..2_000_000 },
            powerKw = powerKw,
            fuel = Fuel.parse(attr(html, "fuel") ?: attr(html, "Brandstof")),
            gearbox = when {
                attr(html, "Transmissie")?.contains("Auto", ignoreCase = true) == true -> Transmission.AUTOMATIC
                attr(html, "Transmissie")?.contains("Handgeschakeld", ignoreCase = true) == true -> Transmission.MANUAL
                else -> null
            },
            bodyType = BodyType.parse(attr(html, "Carrosserievorm") ?: attr(html, "Carrosserie")),
            doors = attr(html, "Aantal deuren")?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 1..7 },
            seats = attr(html, "Aantal zitplaatsen")?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 1..20 },
            color = attr(html, "color") ?: attr(html, "Kleur"),
        )

        return if (info == VehicleInfo()) null else VehicleTextParser.verifiedByPresence(info)
    }

    /** Read a `"<label>":"<value>"` pair out of the embedded attribute object. */
    private fun attr(html: String, label: String): String? =
        Regex("\"${Regex.escape(label)}\"\\s*:\\s*\"([^\"]{1,80})\"")
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
}
