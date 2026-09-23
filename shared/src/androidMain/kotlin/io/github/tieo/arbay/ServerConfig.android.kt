package io.github.tieo.arbay

import android.os.Build

// An emulator reaches the machine it runs on at 10.0.2.2. Build fields are null where no device
// fills them in (host-side unit tests), which counts as not an emulator.
actual fun defaultServerHost(): String =
    if ((Build.FINGERPRINT ?: "").contains("generic") || (Build.MODEL ?: "").contains("Emulator") || (Build.MODEL ?: "").contains("SDK"))
        "10.0.2.2"
    else
        DEFAULT_SERVER_HOST
