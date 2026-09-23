package io.github.tieo.arbay

import android.app.Application
import android.content.Context

/**
 * The process's own context, for the work that has no screen: reading and writing the device's
 * files, scheduling the background poll, raising a notification, opening a link.
 *
 * These used to reach the context through the running MainActivity. With no activity alive,
 * which is every time the background poll runs, reads came back empty and writes were dropped:
 * the poll asked the default server instead of the one chosen in Settings.
 */
open class ArbayApplication : Application() {
    /** The server address and sign-in the build carries. Given by the app module, which is the one
     *  with the build's secrets; this library has none of its own. */
    open val secrets: AppSecrets get() = AppSecrets(serverUrl = null, authHeader = null)

    override fun onCreate() {
        super.onCreate()
        contextOrNull = applicationContext
        current = this
    }

    companion object {
        /** The process's context, or null where no ArbayApplication was created: JVM unit tests
         *  and layout previews, which run the shared code without an Android process. */
        var contextOrNull: Context? = null
            private set

        val context: Context
            get() = contextOrNull ?: error("No ArbayApplication in this process")

        /** The running application, or null in unit tests and previews. */
        var current: ArbayApplication? = null
            private set
    }
}
