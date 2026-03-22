package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
data class Shipping(
    val cost: Money? = null,
    val free: Boolean = false,
    val pickup: Boolean = false,
    val available: Boolean = true,
)
