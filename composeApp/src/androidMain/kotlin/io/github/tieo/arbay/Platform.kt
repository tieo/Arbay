package io.github.tieo.arbay

import java.io.File

actual fun imageModel(address: String): Any =
    if (address.startsWith("http")) address else File(address.removePrefix("file://"))

actual fun schedulePolling(intervalMinutes: Int) {
    FreeItemPollWorker.schedule(ArbayApplication.context, intervalMinutes)
}

actual fun cancelPolling() {
    FreeItemPollWorker.cancel(ArbayApplication.context)
}
