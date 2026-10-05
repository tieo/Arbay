package io.github.tieo.arbay

import kotlinx.browser.window

// The web app is served by the server it talks to, so the server is wherever the page came from,
// and the sign-in in front of it is the browser's own session: no header is baked in, since
// anything in the page's code is readable by whoever loads it.
actual fun appSecrets(): AppSecrets = AppSecrets(serverUrl = window.location.origin, authHeader = null)
