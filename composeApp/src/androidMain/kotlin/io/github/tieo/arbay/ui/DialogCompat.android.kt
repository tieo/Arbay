package io.github.tieo.arbay.ui

import androidx.compose.ui.window.DialogProperties

actual fun fullBleedDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
