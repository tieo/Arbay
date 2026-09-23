package io.github.tieo.arbay.app

import io.github.tieo.arbay.AppSecrets
import io.github.tieo.arbay.ArbayApplication

/** The installed app: the shared Application, given the server and sign-in this build carries. */
class ArbayAndroidApplication : ArbayApplication() {
    override val secrets = AppSecrets(
        serverUrl = BuildConfig.ARBAY_SERVER_URL.ifBlank { null },
        authHeader = BuildConfig.ARBAY_AUTH.ifBlank { null },
    )
}
