package io.github.tieo.arbay.neuland

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The user's own accounts in Neuland's account browser, which Auth signs in: whether the
 * browser is signed in to a site, and asking Auth to sign it in again, which sends its
 * sign-in page to the user's phone.
 */
object NeulandAuth {
    /** Neuland's browser the user's own accounts are signed in to. */
    const val BROWSER = "accounts"

    private val json = Json { ignoreUnknownKeys = true }

    /** Whether the account browser is signed in to [site] ("ebay.de"), asked of the site now. */
    suspend fun standing(site: String): MarketAccount.Standing =
        json.decodeFromJsonElement(Neuland.request(HttpMethod.Get, "browsers/$BROWSER/account/$site", limitS = 60))

    /**
     * Asks Auth to sign the account browser in to [site] again, with the account it was
     * last signed in to with there, for [asker] (in words the user knows). [withKept] starts it with
     * the password Neuland keeps for the account, the user only confirming where the site asks on
     * their phone. A site signed in to through another's account ([through], "google.com" for a
     * site's "Weiter mit Google") takes that account where Neuland knows none of the site's own yet.
     * False where Neuland knows no account (the app then asks for it) or did not take the request.
     */
    suspend fun askAgain(site: String, asker: String, withKept: Boolean = false, through: String? = null): Boolean {
        val known = Neuland.passOn(HttpMethod.Get, "accounts", null)?.second ?: return false
        val accounts = runCatching { Json.parseToJsonElement(known) }.getOrNull()
            ?.let { (it as? JsonObject)?.get("accounts") as? JsonObject }
        fun of(name: String) = accounts?.get(name)?.let { (it as? JsonPrimitive)?.content }
        val account = of(site) ?: through?.let(::of) ?: return false
        val body = buildJsonObject {
            put("site", JsonPrimitive(site))
            put("account", JsonPrimitive(account))
            put("browser", JsonPrimitive(BROWSER))
            put("asker", JsonPrimitive(asker))
            if (withKept) put("again", JsonPrimitive(true))
        }
        val (status, _) = Neuland.passOn(HttpMethod.Post, "sign-ins", body.toString()) ?: return false
        return status in 200..299
    }

    /**
     * Signs the account browser in to [site] again with the password Neuland keeps, waiting up to
     * [waitS] for the user's confirmation on their phone. True once it is signed in.
     */
    suspend fun signInAgain(site: String, asker: String, waitS: Int = 150): Boolean {
        if (!askAgain(site, asker, withKept = true)) return false
        val until = System.currentTimeMillis() + waitS * 1000L
        while (System.currentTimeMillis() < until) {
            kotlinx.coroutines.delay(5_000)
            if (runCatching { standing(site).signedIn }.getOrDefault(false)) return true
        }
        return false
    }
}
