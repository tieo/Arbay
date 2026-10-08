package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
data class Shipping(
    val cost: Money? = null,
    val free: Boolean = false,
    val pickup: Boolean = false,
    val available: Boolean = true,
    /** What delivery to the buyer's door costs, where [cost] is a parcel shop price; from the market's own price list. */
    val doorCost: Money? = null,
    /** The service [doorCost] is the price of, such as "DHL Paket 2 kg". */
    val doorBy: String? = null,
)
