package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.ModelWordEvidence
import io.github.tieo.arbay.crawler.RelevanceFilter
import io.github.tieo.arbay.model.CrawlerEventType
import io.github.tieo.arbay.model.CrawlerSearchEvent
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.SearchQuery
import java.io.File
import kotlin.test.Test
import kotlinx.serialization.json.Json

/**
 * Replays real market answers through the relevance filter and writes, per offer, what the filter
 * decides today and what any proposed rule would need to know about it, so a change to the filter
 * is measured on what the markets actually send before it is made. Not an assertion; read the
 * output.
 *
 * The corpus is a directory of search streams (`/api/crawler/search/stream`, one NDJSON file per
 * search) named by `ARBAY_RELEVANCE_CORPUS`, with the words of each search in `queries.txt` beside
 * them. `ARBAY_EVIDENCE` names a copy of the server's model_word_evidence.json, so words implied by
 * a model are read as the server reads them. The table goes to `ARBAY_RELEVANCE_OUT`.
 */
class RelevanceCorpusProbe {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `replay the corpus`() {
        val dir = System.getenv("ARBAY_RELEVANCE_CORPUS")?.let(::File) ?: return println("ARBAY_RELEVANCE_CORPUS not set")
        System.getenv("ARBAY_EVIDENCE")?.let { path ->
            val copy = File.createTempFile("evidence", ".json").apply { deleteOnExit() }
            File(path).copyTo(copy, overwrite = true)
            ModelWordEvidence.install(copy)
        }
        val queries = File(dir, "queries.txt").readLines().filter { it.isNotBlank() }.associateBy { it.replace(' ', '_').replace('/', '_') }
        val out = File(System.getenv("ARBAY_RELEVANCE_OUT") ?: "relevance-replay.tsv")
        out.printWriter().use { w ->
            w.println(listOf("query", "market", "recorded", "replayed", "reason", "carries", "inTitle", "inText", "price", "title", "withRule").joinToString("\t"))
            dir.listFiles { f -> f.name.endsWith(".ndjson") }!!.sortedBy { it.name }.forEach { file ->
                val words = queries[file.name.removeSuffix(".ndjson")] ?: return@forEach
                val query = SearchQuery(text = words, category = io.github.tieo.arbay.model.MarketGroup.GENERAL)
                file.readLines().filter { it.isNotBlank() }
                    .mapNotNull { runCatching { json.decodeFromString<CrawlerSearchEvent>(it) }.getOrNull() }
                    .filter { it.type == CrawlerEventType.PLATFORM_DONE }
                    .groupBy { it.platform }.mapValues { it.value.last() }
                    .forEach { (market, done) ->
                        val recordedKept = done.listings.map { it.id }.toSet()
                        val answer: List<Listing> = (done.listings + done.dropped.map { it.listing }).distinctBy { it.id }
                        val replay = RelevanceFilter.partition(answer, query, emptyList())
                        val keptIds = replay.kept.map { it.id }.toSet()
                        val reasons = replay.dropped.associate { it.listing.id to it.reason.name }
                        val ruled = RelevanceFilter.partition(answer, query, emptyList(), aWordWhereTheAnswerWritesOne = true).kept.map { it.id }.toSet()
                        answer.forEach { l ->
                            w.println(listOf(
                                words, market,
                                if (l.id in recordedKept) "kept" else "dropped",
                                if (l.id in keptIds) "kept" else "dropped",
                                reasons[l.id] ?: "",
                                RelevanceFilter.carriesAWordOfTheSearch(l, query).toString(),
                                wordsIn(l.title, words).joinToString(","),
                                wordsIn(l.title + " " + (l.description ?: ""), words).joinToString(","),
                                (l.effectivePrice.amount / 100.0).toString(),
                                l.title.replace('\t', ' ').replace('\n', ' '),
                                if (l.id in ruled) "kept" else "dropped",
                            ).joinToString("\t"))
                        }
                    }
            }
        }
        println("written ${out.absolutePath}")
    }

    /** The words of the search [text] contains, spacing and punctuation aside, an umlaut written
     *  either way: "lägler" is in "Laegler" and in "LAGLER". */
    private fun wordsIn(text: String, words: String): List<String> {
        val compact = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        return words.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.filter { word ->
            val w = word.replace(Regex("[^\\p{L}\\p{N}]"), "")
            // A short word is a word of its own, as the filter matches it: the 9 of "Pixel 9" is not in "2019".
            if (w.length <= 2) return@filter text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).contains(w)
            w.isNotEmpty() && listOf(w, w.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss"),
                w.replace("ä", "a").replace("ö", "o").replace("ü", "u")).any { compact.contains(it) }
        }
    }
}
