package io.github.tieo.arbay

import io.github.tieo.arbay.model.SignInLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The place a tapped link asks the app to open (`arbay://open/settings?signin=VINTED_DE`), held
 * until the app's screens take it: the link arrives with the activity, before anything is drawn.
 */
object AppLinks {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun open(link: String?) {
        if (link != null && link.startsWith("${SignInLink.APP_SCHEME}://open")) _pending.value = link
    }

    /** The link once, taken by whoever opens it. */
    fun take(): String? = _pending.value.also { _pending.value = null }
}
