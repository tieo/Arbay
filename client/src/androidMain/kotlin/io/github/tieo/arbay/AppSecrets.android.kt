package io.github.tieo.arbay

// The secrets are compiled into the app module (androidApp), and handed over by its Application.
// Without one (unit tests, previews) there are none, which is the no-server, no-sign-in default.
actual fun appSecrets(): AppSecrets =
    ArbayApplication.current?.secrets ?: AppSecrets(serverUrl = null, authHeader = null)
