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

/**
 * A picture already in hand, for an address that would otherwise be fetched.
 *
 * An image normally arrives on a background thread and appears when the screen recomposes, which
 * is fine in an app and useless to a renderer drawing one synchronous frame: the frame is gone
 * before the picture lands. Whoever draws off-screen puts decoded images here, and a card uses one
 * if it is offered rather than starting a fetch that will never finish in time.
 */
val LocalPreloadedImages = androidx.compose.runtime.compositionLocalOf<(String) -> androidx.compose.ui.graphics.painter.Painter?> { { null } }

/** The widest a column of text and cards may get before it stops being readable. */
val READABLE_WIDTH = 760.dp

val LocalDesktopMode = compositionLocalOf { false }

// When true (set only by the off-screen gallery renderer), the adaptive sheets paint their content
// inline instead of inside a Dialog — an ImageComposeScene cannot capture a Dialog's own window, so
// this is what lets every sheet be rendered to a gallery PNG.
val LocalRenderInline = compositionLocalOf { false }

@Composable
fun AdaptiveSheet(
    onDismiss: () -> Unit,
    widthFraction: Float = 0.55f,
    maxWidth: Int = 640,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalRenderInline.current) {
        Column(Modifier.fillMaxSize(), content = content)
        return
    }
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
    if (LocalRenderInline.current) {
        Column(Modifier.fillMaxSize(), content = content)
        return
    }
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
