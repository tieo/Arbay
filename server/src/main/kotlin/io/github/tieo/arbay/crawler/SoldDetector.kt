package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Detects whether a listing is actually sold/no-longer-available using:
 * 1. Rule-based patterns in title/description (fast, free)
 * 2. Claude Haiku via Anthropic API for ambiguous cases (optional, requires ANTHROPIC_API_KEY env var)
 *
 * Used to catch listings from platforms without native sold-status signaling (Kleinanzeigen, Vinted)
 * and to verify/correct sold status from platforms that do signal it.
 */
object SoldDetector {

    private val log = LoggerFactory.getLogger(SoldDetector::class.java)

    // Regex patterns indicating a listing is no longer available
    private val SOLD_PATTERNS = listOf(
        // German
        Regex("\\bverkauft\\b", RegexOption.IGNORE_CASE),
        Regex("\\bbereits\\s+verkauft\\b", RegexOption.IGNORE_CASE),
        Regex("\\bschon\\s+verkauft\\b", RegexOption.IGNORE_CASE),
        Regex("\\breserviert\\b", RegexOption.IGNORE_CASE),
        Regex("\\bbereits\\s+reserviert\\b", RegexOption.IGNORE_CASE),
        Regex("\\bnicht\\s+mehr\\s+verf[uü]gbar\\b", RegexOption.IGNORE_CASE),
        Regex("\\bbereits\\s+weg\\b", RegexOption.IGNORE_CASE),
        Regex("\\bschon\\s+weg\\b", RegexOption.IGNORE_CASE),
        Regex("\\bvergeben\\b", RegexOption.IGNORE_CASE),
        Regex("\\bnicht\\s+mehr\\s+zu\\s+haben\\b", RegexOption.IGNORE_CASE),
        Regex("^\\s*\\[?verkauft\\]?\\s*[!-]*\\s*$", RegexOption.IGNORE_CASE),
        Regex("\\bverhandelt\\b", RegexOption.IGNORE_CASE),  // "verhandelt" = dealt/agreed
        // Dutch (Marktplaats)
        Regex("\\bverkocht\\b", RegexOption.IGNORE_CASE),
        Regex("\\bnie\\s+meer\\s+beschikbaar\\b", RegexOption.IGNORE_CASE),
        // English
        Regex("\\b(?:already\\s+)?sold\\b", RegexOption.IGNORE_CASE),
        Regex("\\bno\\s+longer\\s+available\\b", RegexOption.IGNORE_CASE),
        Regex("\\bnot\\s+available\\b", RegexOption.IGNORE_CASE),
        Regex("\\breserved\\b", RegexOption.IGNORE_CASE),
        // French (Vinted DE)
        Regex("\\bvendu\\b", RegexOption.IGNORE_CASE),
        Regex("\\br[eé]serv[eé]\\b", RegexOption.IGNORE_CASE),
    )

    // Patterns in titles that indicate "SOLD" prefix/suffix markers sellers commonly add
    private val TITLE_SOLD_MARKERS = listOf(
        Regex("^\\s*(?:SOLD|VERKAUFT|VERKOCHT|VENDU)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:SOLD|VERKAUFT|VERKOCHT|VENDU)\\s*[!*-]*\\s*$", RegexOption.IGNORE_CASE),
        Regex("\\[(?:SOLD|VERKAUFT|VERKOCHT|VENDU)\\]", RegexOption.IGNORE_CASE),
    )

    /**
     * Returns the listing with corrected `sold` flag based on text analysis.
     * If ANTHROPIC_API_KEY is set and the listing is ambiguous, uses Claude Haiku.
     */
    suspend fun classify(listing: Listing): Listing {
        val httpClient = CrawlerRegistry.httpClient
        // Already marked sold by platform — trust it (no need to re-classify)
        if (listing.sold) return listing

        val title = listing.title
        val description = listing.description ?: ""

        // 1. Fast rule-based check
        if (isSoldByRules(title, description)) {
            log.debug("SoldDetector: rule-based sold detection for '${title.take(60)}'")
            return listing.copy(sold = true)
        }

        // 2. LLM-based for ambiguous cases (only if API key is set and description is non-trivial)
        val apiKey = System.getenv("ANTHROPIC_API_KEY")
        if (apiKey != null && description.length > 30) {
            try {
                val likelySold = classifyWithLlm(listing, apiKey)
                if (likelySold) {
                    log.debug("SoldDetector: LLM-based sold detection for '${title.take(60)}'")
                    return listing.copy(sold = true)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log.warn("SoldDetector: LLM call failed for '${title.take(40)}': ${e.message}")
            }
        }

        return listing
    }

    // "500 verkauft", "1.000+ sold", "12 verkocht" is a units-sold counter that marketplaces print on
    // listings that are very much still for sale. Stripped before the sold patterns run, otherwise
    // every popular active listing reads as sold.
    private val QUANTITY_SOLD = Regex(
        """\b\d[\d.,]*\s*\+?\s*(?:verkauft|sold|verkocht|vendus?|venduti?)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun withoutSalesCounts(text: String) = QUANTITY_SOLD.replace(text, " ")

    private fun isSoldByRules(title: String, description: String): Boolean {
        val cleanTitle = withoutSalesCounts(title)
        val cleanDescription = withoutSalesCounts(description)
        if (TITLE_SOLD_MARKERS.any { it.containsMatchIn(cleanTitle) }) return true
        if (SOLD_PATTERNS.any { it.containsMatchIn(cleanTitle) }) return true
        if (SOLD_PATTERNS.any { it.containsMatchIn(cleanDescription) }) return true
        return false
    }

    private suspend fun classifyWithLlm(listing: Listing, apiKey: String): Boolean {
        val httpClient = CrawlerRegistry.httpClient
        val title = listing.title.take(200)
        val desc = listing.description?.take(500) ?: ""
        val prompt = buildString {
            append("Is this second-hand marketplace listing still FOR SALE, or has it been SOLD/is no longer available?\n\n")
            append("Title: $title\n")
            if (desc.isNotBlank()) append("Description: $desc\n")
            append("\nReply with exactly one word: SOLD or FOR_SALE")
        }

        val response = httpClient.post("https://api.anthropic.com/v1/messages") {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(
                """{"model":"claude-haiku-4-5-20251001","max_tokens":5,"messages":[{"role":"user","content":${
                    kotlinx.serialization.json.Json.encodeToString(
                        kotlinx.serialization.json.JsonPrimitive(prompt)
                    )
                }}]}"""
            )
        }

        val body = response.bodyAsText()
        // Response contains the text content in "text" field
        return body.contains("\"SOLD\"") && !body.contains("\"FOR_SALE\"")
    }
}
