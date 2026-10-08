package io.github.tieo.arbay.signin

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageRewriterTest {
    private val base = URI.create("https://login.kleinanzeigen.de/u/login/identifier?state=s1")
    private val page = javaClass.getResource("/fixtures/signin_identifier_synthetic.html")!!.readText()

    @Test
    fun locationsOfAllowedHostsGoThroughTheProxy() {
        assertEquals(
            "/signin-proxy/login.kleinanzeigen.de/u/login/identifier?state=s1",
            PageRewriter.location("/u/login/identifier?state=s1", base),
        )
        assertEquals(
            "/signin-proxy/www.kleinanzeigen.de/m-einloggen-callback.html?code=ok",
            PageRewriter.location("https://www.kleinanzeigen.de/m-einloggen-callback.html?code=ok", base),
        )
        // Already a proxy address: not wrapped twice.
        assertEquals("/signin-proxy/www.kleinanzeigen.de/", PageRewriter.location("/signin-proxy/www.kleinanzeigen.de/", base))
    }

    @Test
    fun locationsOfOtherHostsAreLeftAsTheyAre() {
        assertEquals("https://evil.example/steal?x=1", PageRewriter.location("https://evil.example/steal?x=1", base))
        assertEquals("https://themen.kleinanzeigen.de/ip-eingeschraenkt/", PageRewriter.location("https://themen.kleinanzeigen.de/ip-eingeschraenkt/", base))
    }

    @Test
    fun addressesThatOnlyNameAPlaceOnThePageStayPut() {
        assertNull(PageRewriter.proxied("#top", base))
        assertNull(PageRewriter.proxied("mailto:help@example.test", base))
        assertNull(PageRewriter.proxied("javascript:void(0)", base))
        assertNull(PageRewriter.proxied("data:image/png;base64,AAAA", base))
    }

    @Test
    fun aHostLookalikeIsNotAnAllowedHost() {
        assertNull(PageRewriter.proxied("https://www.kleinanzeigen.de.evil.example/x", base))
        assertNull(PageRewriter.proxied("https://evilkleinanzeigen.de/x", base))
    }

    @Test
    fun htmlRewritesAttributesOfAllowedHostsAndNoOthers() {
        val out = PageRewriter.html(page, base)
        assertTrue(out.contains("""action="/signin-proxy/login.kleinanzeigen.de/u/login/identifier?state=s1""""), out)
        assertTrue(out.contains("""href="/signin-proxy/login.kleinanzeigen.de/favicon.ico""""), out)
        assertTrue(out.contains("""src="/signin-proxy/login.kleinanzeigen.de/static/js/identifier.js""""), out)
        assertTrue(out.contains("""href="/signin-proxy/www.kleinanzeigen.de/m-einloggen-hilfe.html""""), out)
        assertTrue(out.contains("""src="/signin-proxy/login.kleinanzeigen.de/static/img/logo.svg""""), out)
        // The site's other host is not an allowed one, so its address is left for the browser to load as written.
        assertTrue(out.contains("""href="https://themen.kleinanzeigen.de/datenschutz""""), out)
    }

    @Test
    fun htmlRewritesSrcsetCandidatesKeepingTheirDescriptors() {
        val out = PageRewriter.html(page, base)
        assertTrue(out.contains("""srcset="/signin-proxy/login.kleinanzeigen.de/static/img/logo@2x.png 2x, /signin-proxy/login.kleinanzeigen.de/static/img/logo@3x.png 3x""""), out)
    }

    @Test
    fun htmlRewritesUrlsInStylesheetsAndStyleAttributes() {
        val out = PageRewriter.html(page, base)
        assertTrue(out.contains("url('/signin-proxy/login.kleinanzeigen.de/static/img/bg.png')"), out)
        assertTrue(out.contains("@import \"/signin-proxy/login.kleinanzeigen.de/static/css/theme.css\""), out)
        assertFalse(out.contains("https://login.kleinanzeigen.de"), "an absolute address of the login host was left in the page")
    }

    @Test
    fun htmlDropsThePagesOwnPolicyAndIntegrityHashes() {
        val out = PageRewriter.html(page, base)
        assertFalse(out.contains("Content-Security-Policy", ignoreCase = true), out)
        assertFalse(out.contains("integrity="), out)
    }

    @Test
    fun htmlStartsWithAShimThatProxiesRequestsTheScriptsBuild() {
        val out = PageRewriter.html(page, base)
        val head = out.substringBefore("</head>")
        assertTrue(head.contains("<script>") && head.contains("XMLHttpRequest.prototype.open"), head)
        // The shim is the first script of the head, so it runs before the page's own scripts.
        assertTrue(head.indexOf("XMLHttpRequest.prototype.open") < head.indexOf("identifier.js"), head)
    }

    @Test
    fun aBaseElementChangesWhatRelativeAddressesResolveTo() {
        val html = """<html><head><base href="https://login.kleinanzeigen.de/static/"></head><body><img src="logo.png"></body></html>"""
        val out = PageRewriter.html(html, base)
        assertTrue(out.contains("""src="/signin-proxy/login.kleinanzeigen.de/static/logo.png""""), out)
        assertTrue(out.contains("""<base href="/signin-proxy/login.kleinanzeigen.de/static/">"""), out)
    }

    @Test
    fun scriptTextRewritesAbsoluteAddressesIncludingEscapedOnes() {
        val script = """var a = "https://login.kleinanzeigen.de/x"; var b = "https:\/\/www.kleinanzeigen.de\/y"; var c = "//login.kleinanzeigen.de/z";"""
        val out = PageRewriter.script(script)
        assertEquals(
            """var a = "/signin-proxy/login.kleinanzeigen.de/x"; var b = "/signin-proxy/www.kleinanzeigen.de\/y"; var c = "/signin-proxy/login.kleinanzeigen.de/z";""",
            out,
        )
        assertEquals("https://evil.example/x", PageRewriter.script("https://evil.example/x"))
    }

    @Test
    fun cssRewritesUrlsAndImportsOfAllowedHosts() {
        val css = """.a{background:url(/img/a.png)} .b{background:url("https://www.kleinanzeigen.de/b.png")} @import 'https://login.kleinanzeigen.de/c.css'; .c{background:url(data:image/gif;base64,R0lGOD)}"""
        val out = PageRewriter.css(css, base)
        assertTrue(out.contains("url(/signin-proxy/login.kleinanzeigen.de/img/a.png)"), out)
        assertTrue(out.contains("url(\"/signin-proxy/www.kleinanzeigen.de/b.png\")"), out)
        assertTrue(out.contains("@import '/signin-proxy/login.kleinanzeigen.de/c.css'"), out)
        assertTrue(out.contains("url(data:image/gif;base64,R0lGOD)"), out)
    }

    @Test
    fun targetsOfTheProxyAreReadAsSiteAddresses() {
        assertEquals(ProxyTarget("login.kleinanzeigen.de", "/u/login?state=abc"), parseProxyTarget("/signin-proxy/login.kleinanzeigen.de/u/login?state=abc"))
        assertEquals(ProxyTarget("www.kleinanzeigen.de", "/"), parseProxyTarget("/signin-proxy/www.kleinanzeigen.de"))
        assertEquals(ProxyTarget("www.kleinanzeigen.de", "/?x=1"), parseProxyTarget("/signin-proxy/www.kleinanzeigen.de?x=1"))
    }

    @Test
    fun aHostOutsideTheAllowlistIsRefusedAsAProxyTarget() {
        assertNull(parseProxyTarget("/signin-proxy/evil.example/steal"))
        assertNull(parseProxyTarget("/signin-proxy/www.kleinanzeigen.de@evil.example/steal"))
        assertNull(parseProxyTarget("/signin-proxy/themen.kleinanzeigen.de/"))
        assertNull(parseProxyTarget("/api/chat/account"))
    }
}
