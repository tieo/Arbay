package io.github.tieo.arbay

import android.os.Build

actual fun defaultServerHost(): String =
    if (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator") || Build.MODEL.contains("SDK"))
        "10.0.2.2"
    else
        DEFAULT_SERVER_HOST
