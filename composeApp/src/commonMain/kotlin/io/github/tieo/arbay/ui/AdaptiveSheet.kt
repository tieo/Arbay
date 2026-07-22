package io.github.tieo.arbay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding

val LocalDesktopMode = compositionLocalOf { false }

@Composable
fun AdaptiveSheet(
    onDismiss: () -> Unit,
    widthFraction: Float = 0.55f,
    maxWidth: Int = 640,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = fullBleedDialogProperties(),
    ) {
        Surface(
            modifier = if (LocalDesktopMode.current) {
                Modifier
                    .widthIn(max = maxWidth.dp)
                    .fillMaxWidth(widthFraction)
                    .fillMaxHeight(0.85f)
            } else {
                Modifier.fillMaxSize()
            },
            shape = if (LocalDesktopMode.current) RoundedCornerShape(20.dp) else RoundedCornerShape(0.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = if (LocalDesktopMode.current) 6.dp else 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
                content = content,
            )
        }
    }
}

@Composable
fun AdaptiveFormSheet(
    onDismiss: () -> Unit,
    maxWidth: Int = 520,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = fullBleedDialogProperties(),
    ) {
        Surface(
            modifier = if (LocalDesktopMode.current) {
                Modifier
                    .widthIn(max = maxWidth.dp)
                    .fillMaxWidth(0.45f)
            } else {
                Modifier.fillMaxSize()
            },
            shape = if (LocalDesktopMode.current) RoundedCornerShape(20.dp) else RoundedCornerShape(0.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = if (LocalDesktopMode.current) 6.dp else 0.dp,
        ) {
            Column(
                modifier = if (LocalDesktopMode.current) Modifier
                else Modifier.windowInsetsPadding(WindowInsets.systemBars),
                content = content,
            )
        }
    }
}
