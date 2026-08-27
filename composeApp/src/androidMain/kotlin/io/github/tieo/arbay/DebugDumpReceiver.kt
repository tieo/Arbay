package io.github.tieo.arbay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import io.github.tieo.arbay.debug.DebugRegistry
import java.io.File

/**
 * Writes the debug dump (see debug/DebugRegistry.kt) to a file adb can pull with no root, no
 * screenshot and no tap:
 *
 *   adb shell am broadcast -a io.github.tieo.arbay.DEBUG_DUMP -p io.github.tieo.arbay
 *   adb pull /sdcard/Android/data/io.github.tieo.arbay/files/debug_state.json
 *
 * Registered once, in MainActivity, only in debug builds. There is no user-facing switch for
 * this: registering a receiver and writing a file on request costs nothing while idle, so it is
 * always on in debug rather than behind a setting someone has to remember to flip before asking
 * for help.
 */
private const val ACTION_DEBUG_DUMP = "io.github.tieo.arbay.DEBUG_DUMP"

fun Context.registerDebugDumpReceiver() {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = DebugRegistry.dumpJson()
            val dir = context.getExternalFilesDir(null) ?: return
            File(dir, "debug_state.json").writeText(json)
        }
    }
    ContextCompat.registerReceiver(
        this, receiver, IntentFilter(ACTION_DEBUG_DUMP), ContextCompat.RECEIVER_NOT_EXPORTED,
    )
}
