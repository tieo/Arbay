package io.github.tieo.arbay

actual fun appSecrets(): AppSecrets = AppSecrets(
    serverUrl = BuildConfig.ARBAY_SERVER_URL.ifBlank { null },
    authHeader = BuildConfig.ARBAY_AUTH.ifBlank { null },
)
