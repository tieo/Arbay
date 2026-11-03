package io.github.tieo.arbay.neuland

import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray

/**
 * The user's account on a market, as Neuland's browser [browser] is signed in to it on [site]
 * ("kleinanzeigen.de"): its standing, its conversations and what it writes into them, its price
 * offers and its own listings. Every call is Neuland's account route of the same name; an error
 * Neuland answers is a [Neuland.Refusal] carrying whether the account is signed out, the offer
 * declined in the site's words, or the form it filled.
 */
class MarketAccount(private val browser: String, private val site: String) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val path = "browsers/$browser/account/$site"

    private suspend inline fun <reified T> get(sub: String, limitS: Long = 90): T =
        json.decodeFromJsonElement(Neuland.request(HttpMethod.Get, "$path$sub", limitS = limitS))

    private suspend inline fun <reified T> send(method: HttpMethod, sub: String, body: JsonElement?, limitS: Long = 120): T =
        json.decodeFromJsonElement(Neuland.request(method, "$path$sub", body, limitS))

    suspend fun conversations(page: Int = 0): List<Conversation> = get("/conversations?page=$page")
    suspend fun thread(id: String): Thread = get("/conversations/${id.encoded()}")
    suspend fun markRead(id: String) { Neuland.request(HttpMethod.Post, "$path/conversations/${id.encoded()}/read", limitS = 60) }
    suspend fun reply(id: String, text: String): Thread = send(HttpMethod.Post, "/conversations/${id.encoded()}/reply", JsonObject(mapOf("text" to JsonPrimitive(text))))
    suspend fun message(listing: String, text: String): Thread = send(HttpMethod.Post, "/listings/${listing.encoded()}/message", JsonObject(mapOf("text" to JsonPrimitive(text))))
    suspend fun shippingOptions(listing: String): List<ShippingOption> = get("/shipping-options/${listing.encoded()}", 60)
    suspend fun quote(id: String, cents: Long): OfferQuote = get("/conversations/${id.encoded()}/quote?cents=$cents", 60)
    suspend fun offer(id: String, cents: Long, text: String? = null): Thread =
        send(HttpMethod.Post, "/conversations/${id.encoded()}/offer", JsonObject(mapOfNotNull("cents" to JsonPrimitive(cents), "text" to text?.let(::JsonPrimitive))))
    suspend fun listings(): List<OwnListing> = get("/listings")
    suspend fun post(draft: Draft, photos: List<ByteArray>, send: Boolean): Filled = send(
        HttpMethod.Post, "/listings",
        JsonObject(mapOf(
            "draft" to json.encodeToJsonElement(draft),
            "photos" to JsonArray(photos.map { JsonPrimitive(java.util.Base64.getEncoder().encodeToString(it)) }),
            "send" to JsonPrimitive(send),
        )),
        limitS = 300,
    )
    suspend fun edit(listing: String, changes: Changes, send: Boolean): Filled = send(
        HttpMethod.Patch, "/listings/${listing.encoded()}",
        JsonObject(mapOf("changes" to json.encodeToJsonElement(changes), "send" to JsonPrimitive(send))),
        limitS = 200,
    )
    suspend fun delete(listing: String): Boolean =
        send<Deleted>(HttpMethod.Delete, "/listings/${listing.encoded()}", null, 90).deleted

    private fun String.encoded() = java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")
    private fun mapOfNotNull(vararg pairs: Pair<String, JsonElement?>) = pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    @Serializable data class Standing(val signedIn: Boolean, val name: String? = null)
    @Serializable data class Money(val cents: Long, val currency: String = "EUR") {
        val eur: Double get() = cents / 100.0
    }
    @Serializable data class Conversation(
        val id: String,
        val listing: String,
        val title: String = "",
        val image: String? = null,
        /** "buying" or "selling". */
        val side: String,
        val partner: String = "",
        val unread: Int = 0,
        @SerialName("latest_at") val latestAt: String? = null,
        val latest: String? = null,
    )
    @Serializable data class Notice(val by: String? = null, val event: String)
    @Serializable data class Message(
        val id: String,
        /** "me", "them" or "site". */
        val author: String,
        val text: String = "",
        val at: String? = null,
        val pictures: List<String> = emptyList(),
        val offer: Money? = null,
        val notice: Notice? = null,
    )
    @Serializable data class Thread(val conversation: Conversation, val asking: Money? = null, val messages: List<Message> = emptyList())
    @Serializable data class ShippingOption(val id: String = "", val name: String, val price: Money, val liability: Money? = null)
    @Serializable data class OfferQuote(
        val price: Money,
        val fee: Money,
        @SerialName("cheapest_shipping") val cheapestShipping: Money? = null,
        val total: Money,
    )
    @Serializable data class OwnListing(
        val id: String,
        val title: String,
        val url: String? = null,
        val image: String? = null,
        val price: Money? = null,
        @SerialName("price_kind") val priceKind: String? = null,
        val state: JsonElement? = null,
        val created: String? = null,
        val ends: String? = null,
        val views: Long? = null,
        val watchers: Long? = null,
        val replies: Long? = null,
    )
    @Serializable data class Draft(
        val category: String,
        val title: String,
        val description: String,
        /** The form's questions and answers by the label it shows, in order. */
        val details: List<List<String>>,
        val price: Money? = null,
        /** "fixed", "negotiable" or "free". */
        @SerialName("price_kind") val priceKind: String,
        val shipping: Boolean,
        @SerialName("buy_now") val buyNow: Boolean,
        val photos: List<String> = emptyList(),
    )
    @Serializable data class Changes(
        val title: String? = null,
        val description: String? = null,
        val price: Money? = null,
        @SerialName("price_kind") val priceKind: String? = null,
        val shipping: Boolean? = null,
        @SerialName("buy_now") val buyNow: Boolean? = null,
    )
    @Serializable data class Filled(val form: JsonObject = JsonObject(emptyMap()), val sent: Boolean, val listing: String? = null, val picture: String? = null)
    @Serializable private data class Deleted(val deleted: Boolean)
}
