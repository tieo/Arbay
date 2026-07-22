package io.github.tieo.arbay.ui

import androidx.compose.ui.window.DialogProperties

/** Dialog properties for a full-bleed adaptive sheet. Android additionally draws under the system
 *  bars (decorFitsSystemWindows = false), a parameter that only exists on Android's DialogProperties;
 *  the other targets use the common properties. */
expect fun fullBleedDialogProperties(): DialogProperties
