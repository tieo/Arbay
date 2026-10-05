package io.github.tieo.arbay

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import io.github.tieo.arbay.routes.isOnPublicInternet
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageProxyTest {
    private fun refuses(address: String) = testApplication {
        application { module() }
        val response = client.get("/api/image") { parameter("url", address) }
        assertEquals(HttpStatusCode.BadRequest, response.status, address)
    }

    @Test fun `this machine is not fetched`() = refuses("http://127.0.0.1:8090/api/products")

    @Test
    fun `private networks are not on the public internet`() {
        fun address(vararg octets: Int) = InetAddress.getByAddress(ByteArray(octets.size) { octets[it].toByte() })
        assertFalse(isOnPublicInternet(address(10, 0, 0, 1)))
        assertFalse(isOnPublicInternet(address(172, 16, 4, 2)))
        assertFalse(isOnPublicInternet(address(169, 254, 1, 1)))
        assertFalse(isOnPublicInternet(address(0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
        assertTrue(isOnPublicInternet(address(1, 1, 1, 1)))
    }

    @Test fun `a name for this machine is not fetched`() = refuses("http://localhost/")

    @Test fun `only web addresses are fetched`() = refuses("file:///etc/passwd")
}
