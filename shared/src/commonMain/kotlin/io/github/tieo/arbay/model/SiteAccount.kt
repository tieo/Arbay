package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/**
 * Where signing Neuland's account browser in to one of the user's accounts (Google, PayPal, a shop)
 * stands, as Neuland reports it.
 */
@Serializable
data class SiteSignIn(
    /** asked, signing-in, phone, code, shows, signed-in, failed, called-off or signed-out. */
    val state: String,
    /** The number the phone's prompt shows, to tap there. */
    val number: String? = null,
    /** Where the site says it sent the code. */
    val hint: String? = null,
    /** The account signed in. */
    val who: String? = null,
    /** Why the site refused, in its words. */
    val reason: String? = null,
    /** What the site shows where the sign-in does not know the page, in the page's own words. */
    val text: String? = null,
) {
    /** Whether the sign-in is open: waiting for the password on Neuland Sign's page, the browser
     *  working, or waiting for the phone or a code. */
    val running: Boolean get() = state in setOf("asked", "signing-in", "phone", "code", "shows")

    /** What stands, in words: a state Neuland names that is not listed here shows by that name. */
    val status: String
        get() = when (state) {
            "asked" -> "waiting for the password"
            "signing-in" -> "signing in"
            "phone" -> number?.let { "waiting for your phone, tap $it" } ?: "waiting for your phone"
            "code" -> "waiting for the code"
            "shows" -> text?.let { "the site shows: $it" } ?: state
            "signed-in" -> who ?: "signed in"
            "failed" -> reason ?: "failed"
            "called-off" -> "called off"
            "signed-out" -> "signed out"
            else -> state
        }
}

/** Every site Neuland signs its account browser in to, where the latest sign-in of each stands (a
 *  site without one has none on record), that sign-in's Neuland Sign page, and the account each was
 *  last signed in to with. */
@Serializable
data class SiteSignIns(
    val sites: List<String> = emptyList(),
    val steps: Map<String, SiteSignIn> = emptyMap(),
    val pages: Map<String, String> = emptyMap(),
    val accounts: Map<String, String> = emptyMap(),
)

/** A sign-in Neuland Sign holds open: its id, and its page where the password is given. */
@Serializable
data class AskedSignIn(val id: String, val page: String)
