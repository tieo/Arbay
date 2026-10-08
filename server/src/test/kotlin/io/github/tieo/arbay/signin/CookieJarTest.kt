package io.github.tieo.arbay.signin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class CookieJarTest {
    private val now = Instant.parse("2026-10-08T10:00:00Z")

    @Test
    fun aHostOnlyCookieGoesBackToItsHostAlone() {
        val jar = CookieJar()
        jar.store("login.kleinanzeigen.de", "/u/login/identifier", "did=abc; Path=/; HttpOnly; Secure", now)
        assertEquals("did=abc", jar.header("login.kleinanzeigen.de", "/u/login/password", now))
        assertNull(jar.header("www.kleinanzeigen.de", "/", now))
    }

    @Test
    fun aDomainCookieGoesBackToTheSiteAndItsSubdomains() {
        val jar = CookieJar()
        jar.store("www.kleinanzeigen.de", "/m-einloggen.html", "CSRF-TOKEN=t1; Domain=.kleinanzeigen.de; Path=/", now)
        assertEquals("CSRF-TOKEN=t1", jar.header("login.kleinanzeigen.de", "/authorize", now))
        assertEquals("CSRF-TOKEN=t1", jar.header("www.kleinanzeigen.de", "/", now))
    }

    @Test
    fun aCookieForAnotherSiteIsRefused() {
        val jar = CookieJar()
        jar.store("www.kleinanzeigen.de", "/", "x=1; Domain=evil.example", now)
        jar.store("www.kleinanzeigen.de", "/", "y=1; Domain=kleinanzeigen.de.evil.example", now)
        // A page on an allowed host cannot set a cookie for a domain that host is not part of.
        jar.store("login.kleinanzeigen.de", "/", "z=1; Domain=www.kleinanzeigen.de", now)
        assertNull(jar.header("www.kleinanzeigen.de", "/", now))
        assertNull(jar.header("evil.example", "/", now))
    }

    @Test
    fun pathsOfCookiesAreMatchedOnSegments() {
        val jar = CookieJar()
        jar.store("login.kleinanzeigen.de", "/u/login/identifier", "a=1; Path=/u/login", now)
        assertEquals("a=1", jar.header("login.kleinanzeigen.de", "/u/login/password", now))
        assertEquals("a=1", jar.header("login.kleinanzeigen.de", "/u/login", now))
        assertNull(jar.header("login.kleinanzeigen.de", "/u/loginx", now))
    }

    @Test
    fun aDefaultPathIsTheDirectoryTheCookieWasSetIn() {
        val jar = CookieJar()
        jar.store("login.kleinanzeigen.de", "/u/login/identifier", "b=2", now)
        assertEquals("b=2", jar.header("login.kleinanzeigen.de", "/u/login/password", now))
        assertNull(jar.header("login.kleinanzeigen.de", "/authorize", now))
    }

    @Test
    fun maxAgeZeroRemovesACookie() {
        val jar = CookieJar()
        jar.store("www.kleinanzeigen.de", "/", "c=1; Domain=.kleinanzeigen.de", now)
        jar.store("www.kleinanzeigen.de", "/", "c=; Domain=.kleinanzeigen.de; Max-Age=0", now)
        assertNull(jar.header("www.kleinanzeigen.de", "/", now))
    }

    @Test
    fun aCookieStopsBeingSentWhenItsExpiryPasses() {
        val jar = CookieJar()
        jar.store("www.kleinanzeigen.de", "/", "short=1; Max-Age=60", now)
        jar.store("www.kleinanzeigen.de", "/", "long=2; Max-Age=36000", now)
        assertEquals("short=1; long=2", jar.header("www.kleinanzeigen.de", "/", now))
        assertEquals("long=2", jar.header("www.kleinanzeigen.de", "/", now + 2.hours))
    }

    @Test
    fun anExpiresDateInThePastRemovesTheCookie() {
        val jar = CookieJar()
        jar.store("www.kleinanzeigen.de", "/", "old=1; Expires=Thu, 01 Jan 2026 00:00:00 GMT", now)
        assertNull(jar.header("www.kleinanzeigen.de", "/", now))
    }

    @Test
    fun handOverKeepsEveryCookieWithItsDomainAndFlags() {
        val jar = CookieJar()
        jar.store("login.kleinanzeigen.de", "/", "did=1; Secure; HttpOnly", now)
        jar.store("www.kleinanzeigen.de", "/", "session=2; Domain=.kleinanzeigen.de; Path=/; Max-Age=3600", now)
        val all = jar.all(now).associateBy { it.name }
        assertEquals(ProxyCookie("did", "1", "login.kleinanzeigen.de", true, "/", true, true, null), all["did"])
        assertEquals(false, all.getValue("session").hostOnly)
        assertEquals("kleinanzeigen.de", all.getValue("session").domain)
        assertTrue(all.getValue("session").expires != null)
    }

    @Test
    fun cookiesSetAgainReplaceTheEarlierOnes() {
        val jar = CookieJar()
        jar.store("www.kleinanzeigen.de", "/", "k=old; Domain=.kleinanzeigen.de", now)
        jar.store("www.kleinanzeigen.de", "/", "k=new; Domain=.kleinanzeigen.de", now)
        assertEquals("k=new", jar.header("www.kleinanzeigen.de", "/", now))
    }
}
