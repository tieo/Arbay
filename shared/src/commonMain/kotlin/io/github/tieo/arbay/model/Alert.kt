package io.github.tieo.arbay.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Alert(
    val id: String,
    val productId: String,
    val listingId: String,
    val type: AlertType,
    val message: String,
    val createdAt: Instant,
    val read: Boolean = false,
)
