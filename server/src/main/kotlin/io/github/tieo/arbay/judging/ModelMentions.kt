package io.github.tieo.arbay.judging

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ModelWish
import io.github.tieo.arbay.model.ProductWishes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Whether an ad whose text names a wanted model, and whose title names none, sells that model.
 *
 * An accessory's text names the machine it fits, a used SSD's the console it sat in. Of 348 such
 * offers across 13 kinds of product, 207 were something else. Word and price rules were measured
 * first and reached 85 % of those at the cost of one real offer in five, so Haiku reads each one:
 * it called 206 of the 207 something else and none of them the model. Of 134 real offers it called
 * 23 something else: 21 a longer model than the one wished for (an iPhone 15 Pro Max against
 * "iPhone 15 Pro"), which is right, and 2 mixed bundles.
 *
 * Anything but a clear "something else" leaves the model standing, and that verdict only takes
 * the model's points away: the offer stays listed.
 */
object ModelMentions {
    const val NOT = "NOT"

    private const val START_CHARS = 220
    private const val BEFORE_CHARS = 90
    private const val AFTER_CHARS = 60
    private const val PLACES_SHOWN = 3

    /** The prompt the measurement above was made with; a change to it wants measuring again. */
    private val SYSTEM = """
        You read second-hand and shop offers for a buyer who wants certain product models. Each offer's text names one or more of those models; its title names none of them.
        For each offer decide what is actually being sold:
        REAL: the thing for sale is itself one of the models the offer's text names; a set or bundle whose main item is that model counts.
        NOT: anything else: an accessory, spare part, consumable or case, a product merely compatible with or usable in that model, another product the model is only compared with, or the word means something else entirely.
        UNSURE: the given text does not let you tell.
        Words may be German, English, French, Italian or other languages. Judge each offer on its own from its title, price and text.
        Answer only with a JSON array of objects {"i": <offer number>, "v": "REAL"|"NOT"|"UNSURE"}, one per offer, nothing else.
    """.trimIndent()

    /** What an ad's text says of the wanted models. */
    data class Mention(
        val listing: Listing,
        /** The most wanted model named, the one the offer would count as. */
        val model: ModelWish,
        val named: List<String>,
        val start: String,
        val where: List<String>,
    )

    val enabled: Boolean get() = OfferJudge.enabled

    /** What [text], the ad's own, says of [wishes]' models, or null where it names none. */
    internal fun of(listing: Listing, text: String, wishes: ProductWishes): Mention? {
        val folded = FitScore.folded(FitScore.withoutTags(text))
        val named = FitScore.modelsNamed(folded, wishes.models)
        val model = named.maxByOrNull { it.worth } ?: return null
        val where = named.mapNotNull { m -> m.words.flatMap { FitScore.spans(folded, it) }.minByOrNull { it.first } }
            .take(PLACES_SHOWN)
            .map { folded.substring((it.first - BEFORE_CHARS).coerceAtLeast(0), (it.last + 1 + AFTER_CHARS).coerceAtMost(folded.length)).trim() }
        return Mention(listing, model, named.map { it.name }, FitScore.folded(text).take(START_CHARS).trim(), where)
    }

    internal fun line(number: Int, m: Mention): String = buildJsonObject {
        put("i", number)
        put("title", m.listing.title)
        put("price", "${m.listing.price.amount / 100.0} ${m.listing.price.currency.name}")
        put("text_start", m.start)
        put("models_named", JsonArray(m.named.map(::JsonPrimitive)))
        put("where_named", JsonArray(m.where.map(::JsonPrimitive)))
    }.toString()

    /** Haiku's verdict per offer id, for the offers it answered about; nothing when it is off. */
    suspend fun read(mentions: List<Mention>): Map<String, String> {
        if (!enabled) return emptyMap()
        return mentions.chunked(OfferJudge.BATCH).flatMap { batch ->
            val prompt = "Offers:\n" + batch.mapIndexed(::line).joinToString("\n")
            OfferJudge.answers(SYSTEM, prompt, "${batch.size} model mentions").mapNotNull { (i, v) -> batch.getOrNull(i)?.let { it.listing.id to v } }
        }.toMap()
    }
}
