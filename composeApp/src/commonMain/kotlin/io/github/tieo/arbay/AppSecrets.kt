package io.github.tieo.arbay

/**
 * Server URL and non-interactive auth header, baked in at build time from the
 * gitignored secret.properties. Lets the app reach the Authelia-gated server without a
 * manual sign-in. Only the Android build wires real values; other targets get nulls and
 * fall back to the localhost/LAN default with no auth.
 */
data class AppSecrets(val serverUrl: String?, val authHeader: String?)

expect fun appSecrets(): AppSecrets
