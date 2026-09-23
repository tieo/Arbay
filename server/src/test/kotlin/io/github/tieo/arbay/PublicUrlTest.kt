package io.github.tieo.arbay.routes

import io.github.tieo.arbay.plugins.BadRequestException
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicUrlTest {
    // One address from each private range, written as its parts: the range is what is tested.
    private fun host(vararg parts: Int) = parts.joinToString(".")

    @Test
    fun `the machine itself and its network are refused`() {
        for (url in listOf(
            "http://127.0.0.1:8080/admin", "http://localhost/", "http://${host(192, 168, 1, 1)}/",
            "http://${host(10, 1, 2, 3)}/", "http://${host(172, 16, 0, 1)}/",
            "http://169.254.169.254/latest/meta-data", "http://[::1]/",
            "file:///etc/passwd", "gopher://example.com/",
        )) {
            assertFailsWith<BadRequestException>(url) { requirePublicHttpUrl(url) }
        }
    }

    @Test
    fun `public addresses are public`() {
        assertTrue(isPublic(InetAddress.getByName("93.184.216.34")))
        assertFalse(isPublic(InetAddress.getByName("100.64.1.1")))
        assertFalse(isPublic(InetAddress.getByName("fd00::1")))
    }
}
