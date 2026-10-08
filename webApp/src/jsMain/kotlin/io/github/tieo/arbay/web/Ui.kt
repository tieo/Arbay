package io.github.tieo.arbay.web

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.AttrsScope
import org.w3c.dom.HTMLImageElement
import androidx.compose.runtime.key
import io.github.tieo.arbay.format
import io.github.tieo.arbay.model.Money
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Line icons, 24×24 on a 2px stroke, drawn from the Lucide set (ISC licence). Each is the inside of
 * an `<svg>`; [Icon] wraps it.
 */
enum class Glyph(val svg: String) {
    Search("""<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>"""),
    Car("""<path d="M5 17h14v-5l-2-5H7l-2 5z"/><circle cx="7.5" cy="17.5" r="1.5"/><circle cx="16.5" cy="17.5" r="1.5"/><path d="M5 12h14"/>"""),
    Gift("""<rect x="3" y="8" width="18" height="4" rx="1"/><path d="M12 8v13"/><path d="M19 12v7a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-7"/><path d="M7.5 8a2.5 2.5 0 0 1 0-5C11 3 12 8 12 8s1-5 4.5-5a2.5 2.5 0 0 1 0 5"/>"""),
    Settings("""<path d="M4 21v-7"/><path d="M4 10V3"/><path d="M12 21v-9"/><path d="M12 8V3"/><path d="M20 21v-5"/><path d="M20 12V3"/><path d="M1 14h6"/><path d="M9 8h6"/><path d="M17 16h6"/>"""),
    Bookmark("""<path d="m19 21-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/>"""),
    Bell("""<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/>"""),
    Close("""<path d="M18 6 6 18"/><path d="m6 6 12 12"/>"""),
    External("""<path d="M15 3h6v6"/><path d="M10 14 21 3"/><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>"""),
    EyeOff("""<path d="m2 2 20 20"/><path d="M6.7 6.7C3.9 8.5 2 12 2 12s3.6 7 10 7c1.9 0 3.6-.6 5-1.5"/><path d="M10.6 5.1A10.4 10.4 0 0 1 12 5c6.4 0 10 7 10 7a18 18 0 0 1-2.2 3.2"/><path d="M14.1 14.1a3 3 0 0 1-4.2-4.2"/>"""),
    Store("""<path d="M3 9 4.5 4h15L21 9"/><path d="M4 9v11h16V9"/><path d="M3 9h18"/><path d="M9 20v-6h6v6"/>"""),
    Tag("""<path d="M12.6 2.6A2 2 0 0 0 11.2 2H4a2 2 0 0 0-2 2v7.2a2 2 0 0 0 .6 1.4l8.7 8.7a2.4 2.4 0 0 0 3.4 0l6.6-6.6a2.4 2.4 0 0 0 0-3.4z"/><circle cx="7.5" cy="7.5" r="1"/>"""),
    Words("""<path d="M4 7V4h16v3"/><path d="M9 20h6"/><path d="M12 4v16"/>"""),
    Rows("""<path d="M3 6h18"/><path d="M3 12h18"/><path d="M3 18h18"/>"""),
    Photos("""<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>"""),
    Refresh("""<path d="M21 12a9 9 0 1 1-3-6.7L21 8"/><path d="M21 3v5h-5"/>"""),
    Back("""<path d="m15 18-6-6 6-6"/>"""),
    Check("""<path d="M20 6 9 17l-5-5"/>"""),
    Heart("""<path d="M19 14c1.5-1.5 3-3.2 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.8 0-3 .5-4.5 2-1.5-1.5-2.7-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4 3 5.5l7 7z"/>"""),
    No("""<circle cx="12" cy="12" r="9"/><path d="m5.7 5.7 12.6 12.6"/>"""),
    Undo("""<path d="M9 14 4 9l5-5"/><path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11"/>"""),
    Pin("""<path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0"/><circle cx="12" cy="10" r="3"/>"""),
    Clock("""<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>"""),
    Chat("""<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>"""),
    Send("""<path d="M22 2 11 13"/><path d="m22 2-7 20-4-9-9-4z"/>"""),
    Pencil("""<path d="M17 3a2.8 2.8 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5z"/>"""),
    Chart("""<path d="M3 3v18h18"/><path d="M7 15v3"/><path d="M12 10v8"/><path d="M17 6v12"/>"""),
}

/** An icon, sized to the text around it unless [size] says otherwise. Decorative: whatever it marks
 *  carries its own label. The SVG is written once as the element mounts, so a different glyph in the
 *  same place is a new element. */
@Composable
fun Icon(glyph: Glyph, size: Int = 18) = key(glyph, size) {
    Span({
        classes("icon-glyph")
        attr("aria-hidden", "true")
        ref { element ->
            element.innerHTML = """<svg viewBox="0 0 24 24" width="$size" height="$size" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${glyph.svg}</svg>"""
            onDispose { }
        }
    }) {}
}

/** A button that is only an icon, named for whoever cannot see it. */
@Composable
fun IconButton(glyph: Glyph, label: String, pressed: Boolean? = null, onClick: () -> Unit) {
    Button(attrs = {
        attr("type", "button")
        classes(*listOfNotNull("icon-button", "on".takeIf { pressed == true }).toTypedArray())
        attr("title", label)
        attr("aria-label", label)
        pressed?.let { attr("aria-pressed", it.toString()) }
        onClick { it.stopPropagation(); onClick() }
    }) { Icon(glyph) }
}

/** The one thing a screen is for, drawn as such. */
@Composable
fun PrimaryButton(text: String, glyph: Glyph? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Button(attrs = {
        classes("primary")
        if (!enabled) attr("disabled", "")
        onClick { onClick() }
    }) {
        glyph?.let { Icon(it) }
        Text(text)
    }
}

/** Everything else that can be pressed. */
@Composable
fun QuietButton(text: String, glyph: Glyph? = null, pressed: Boolean? = null, onClick: () -> Unit) {
    Button(attrs = {
        attr("type", "button")
        classes(*listOfNotNull("quiet", "on".takeIf { pressed == true }).toTypedArray())
        pressed?.let { attr("aria-pressed", it.toString()) }
        onClick { onClick() }
    }) {
        glyph?.let { Icon(it) }
        Text(text)
    }
}

/** A choice that is on or off by itself. */
@Composable
fun Chip(text: String, on: Boolean, onToggle: () -> Unit) {
    Button(attrs = {
        attr("type", "button")
        classes(*listOfNotNull("chip", "on".takeIf { on }).toTypedArray())
        attr("aria-pressed", on.toString())
        onClick { onToggle() }
    }) {
        if (on) Icon(Glyph.Check, 14)
        Text(text)
    }
}

/** A switch with its label beside it. */
@Composable
fun Switch(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Label(attrs = { classes("switch") }) {
        Input(InputType.Checkbox) {
            checked(on)
            attr("role", "switch")
            onChange { onChange(it.value) }
        }
        Span({ classes("switch-track") }) {}
        Span { Text(label) }
    }
}

/** A price as every screen shows it. [lowest] marks the cheapest offer: the accent colour, and a
 *  rule beneath it so it stays apart without colour. */
@Composable
fun Price(money: Money, lowest: Boolean = false, big: Boolean = false) {
    Span({ classes(*listOfNotNull("price", "lowest".takeIf { lowest }, "big".takeIf { big }).toTypedArray()) }) {
        Text(money.format())
    }
}

/** A photo the market has since taken down keeps its place as an empty frame instead of the
 *  browser's broken-image mark. */
fun AttrsScope<HTMLImageElement>.onPhotoGone() {
    addEventListener("error") { event ->
        val img = event.target as HTMLImageElement
        img.src = "data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw=="
    }
}
