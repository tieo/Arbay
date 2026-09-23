package io.github.tieo.arbay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Register notification channels early so they appear in system settings
        NotificationHelper.ensureChannels(this)

        // Schedule background polling (WorkManager handles dedup)
        FreeItemPollWorker.schedule(this)

        // Debug-only: lets a dev pull the app's live state over adb instead of screenshotting
        // through a session. See DebugDumpReceiver.kt.
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) registerDebugDumpReceiver()

        setContent { App() }
    }

}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
