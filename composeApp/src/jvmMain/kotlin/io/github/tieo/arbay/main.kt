package io.github.tieo.arbay

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Arbay",
    ) {
        App()
    }
}