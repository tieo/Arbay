package io.github.tieo.arbay.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.tieo.arbay.design.Brightness
import io.github.tieo.arbay.design.LookChoice
import io.github.tieo.arbay.design.Palette
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/** Hex for a stylesheet: 0x2E7D32 reads "#2e7d32". */
private fun css(color: Long): String = "#" + color.toString(16).padStart(6, '0')

/**
 * Puts the chosen look on the page: its colours as the stylesheet's variables, its corners and
 * density, and its brightness, following the system's own setting live where that is the choice.
 */
@Composable
fun ApplyLook() {
    var systemDark by remember { mutableStateOf(window.matchMedia("(prefers-color-scheme: dark)").matches) }
    DisposableEffect(Unit) {
        val query = window.matchMedia("(prefers-color-scheme: dark)")
        val listener: (org.w3c.dom.events.Event) -> Unit = { systemDark = query.matches }
        query.addEventListener("change", listener)
        onDispose { query.removeEventListener("change", listener) }
    }
    val look = LookChoice.look
    val dark = when (LookChoice.brightness) {
        Brightness.SYSTEM -> systemDark
        Brightness.LIGHT -> false
        Brightness.DARK -> true
    }
    val palette = if (dark) look.dark else look.light
    DisposableEffect(look, dark) {
        val root = document.documentElement as HTMLElement
        apply(root, palette)
        root.style.setProperty("--radius", "${look.radius}px")
        root.style.setProperty("color-scheme", if (dark) "dark" else "light")
        root.setAttribute("data-look", look.id)
        root.setAttribute("data-density", look.density.toString())
        root.setAttribute("data-mono-prices", look.monoPrices.toString())
        onDispose { }
    }
}

private fun apply(root: HTMLElement, p: Palette) {
    mapOf(
        "--bg" to p.background, "--surface" to p.surface, "--raised" to p.raised, "--line" to p.line,
        "--text" to p.text, "--muted" to p.muted, "--accent" to p.accent, "--on-accent" to p.onAccent,
        "--accent-soft" to p.accentSoft, "--lowest" to p.lowest, "--selected" to p.selected, "--danger" to p.danger,
    ).forEach { (name, color) -> root.style.setProperty(name, css(color)) }
}
