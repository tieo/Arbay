package io.github.tieo.arbay.neuland

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The user's own accounts in Neuland's account browser, which Neuland Sign signs in: whether the
 * browser is signed in to a site, and asking Neuland Sign to sign it in again, which sends its
 * sign-in page to the user's phone.
 */
object NeulandSign {
    /** Neuland's browser the user's own accounts are signed in to. */
    const val BROWSER = "accounts"

    private val json = Json { ignoreUnknownKeys = true }

    /** Whether the account browser is signed in to [site] ("ebay.de"), asked of the site now. */
    suspend fun standing(site: String): MarketAccount.Standing =
        json.decodeFromJsonElement(Neuland.request(HttpMethod.Get, "browsers/$BROWSER/account/$site", limitS = 60))

    /**
     * Asks Neuland Sign to sign the account browser in to [site] again, with the account it was
     * last signed in to with there, for [asker] (in words the user knows). False where Neuland
     * knows no account for the site (the app then asks for it) or did not take the request.
     */
    suspend fun askAgain(site: String, asker: String): Boolean {
        val known = Neuland.passOn(HttpMethod.Get, "accounts", null)?.second ?: return false
        val account = runCatching { Json.parseToJsonElement(known) }.getOrNull()
            ?.let { (it as? JsonObject)?.get("accounts") as? JsonObject }
            ?.get(site)?.let { (it as? JsonPrimitive)?.content } ?: return false
        val body = buildJsonObject {
            put("site", JsonPrimitive(site))
            put("account", JsonPrimitive(account))
            put("browser", JsonPrimitive(BROWSER))
            put("asker", JsonPrimitive(asker))
        }
        val (status, _) = Neuland.passOn(HttpMethod.Post, "sign-ins", body.toString()) ?: return false
        return status in 200..299
    }
}
