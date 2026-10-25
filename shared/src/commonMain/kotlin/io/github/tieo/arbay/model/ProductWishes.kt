package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/**
 * What a search for a thing that is not a vehicle weighs an offer by, so it can be scored as a
 * whole the way a vehicle search's criteria are: which of the models that do the job it is, what
 * it costs, and what else the ad says.
 *
 * A machine search has several models that would all do, some better than others: a Lägler
 * HUMMEL is the parquet sander, a PROFIT does the job lighter and cheaper, and an old Frank FSR 20
 * does it with no parts left to keep it running. Each is named by the words its ads use and worth
 * a share of a perfect fit; an offer that names none of them is weighed as not stated.
 */
@Serializable
data class ProductWishes(
    /** The models that would do, each with the words naming it in an ad and what it is worth. */
    val models: List<ModelWish> = emptyList(),
    /** A price that fits fully, in whole euros landed. */
    val idealPriceEur: Int? = null,
    /** The price past which an offer stops fitting at all, in whole euros landed. */
    val maxPriceEur: Int? = null,
    /** Words that rule an offer out wherever its title or text says them unnegated: "400 V". */
    val ruledOutWords: List<String> = emptyList(),
    /** Words that make an offer better: what comes with it, or a part said to be new. */
    val plusWords: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() =
        models.isEmpty() && idealPriceEur == null && maxPriceEur == null && ruledOutWords.isEmpty() && plusWords.isEmpty()
}

/** One model that would do: its [name], the [words] naming it in an ad (any one is enough), and
 *  [worth], the share of a perfect fit being this model gives, from 0 to 1. */
@Serializable
data class ModelWish(val name: String, val words: List<String>, val worth: Double)

/** A reviewer's verdict on which of a search's models an offer is; null for none of them. */
@Serializable
data class ModelCheck(val model: String? = null)
