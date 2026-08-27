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
    // Exported: a NOT_EXPORTED receiver took the shell-sent broadcast fine on the emulator but was
    // silently dropped before reaching the app on a real Pixel (a real device or its patch level
    // enforces this more strictly than the AOSP emulator image does). The action string is
    // unguessable enough, and all a broadcast can trigger is one write of already-visible app
    // state to app-private storage, so exported is an acceptable trade to have this work at all.
    ContextCompat.registerReceiver(
        this, receiver, IntentFilter(ACTION_DEBUG_DUMP), ContextCompat.RECEIVER_EXPORTED,
    )
}
