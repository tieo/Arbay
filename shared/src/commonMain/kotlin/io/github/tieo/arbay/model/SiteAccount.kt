package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/**
 * Where signing Neuland's account browser in to one of the user's accounts (Google, PayPal, a shop)
 * stands, as Neuland reports it.
 */
@Serializable
data class SiteSignIn(
    /** signed-out, signing-in, phone, code, signed-in or failed. */
    val state: String,
    /** The number the phone's prompt shows, to tap there. */
    val number: String? = null,
    /** Where the site says it sent the code. */
    val hint: String? = null,
    /** The account signed in. */
    val who: String? = null,
    /** Why the site refused, in its words. */
    val reason: String? = null,
) {
    /** Whether the sign-in is under way: the browser working, or waiting for the phone or a code. */
    val running: Boolean get() = state in setOf("signing-in", "phone", "code")
}

/** Neuland's key a password is sealed to, every site's sign-in, and the account each was last
 *  signed in to with, so the next sign-in asks only the password. */
@Serializable
data class SiteSignIns(
    val key: String,
    val steps: Map<String, SiteSignIn> = emptyMap(),
    val accounts: Map<String, String> = emptyMap(),
)

/** What signing in to a site is sent with: the account, and the password sealed to Neuland's key. */
@Serializable
data class SiteSignInRequest(val account: String, val password: String)

/** A code a site asked for, sealed to Neuland's key. */
@Serializable
data class SiteSignInCode(val code: String)
