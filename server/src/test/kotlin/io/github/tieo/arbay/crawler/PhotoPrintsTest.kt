package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Location
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.Seller
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class PhotoPrintsTest {

    /** A van-ish picture: sky, a light box with a dark grille, ground; [seed] moves it about. */
    private fun photo(seed: Int, width: Int = 640, height: Int = 480): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        val r = java.util.Random(seed.toLong())
        g.color = Color(120 + r.nextInt(100), 160 + r.nextInt(60), 230); g.fillRect(0, 0, width, height / 2)
        g.color = Color(90 + r.nextInt(60), 90, 80); g.fillRect(0, height / 2, width, height / 2)
        // A real photo is busy all over: a few large shapes and many small ones.
        repeat(6) {
            g.color = Color(r.nextInt(256), r.nextInt(256), r.nextInt(256))
            g.fillRect(r.nextInt(width), r.nextInt(height), width / 4 + r.nextInt(width / 3), height / 5 + r.nextInt(height / 3))
        }
        repeat(60) {
            g.color = Color(r.nextInt(256), r.nextInt(256), r.nextInt(256))
            g.fillRect(r.nextInt(width), r.nextInt(height), width / 12 + r.nextInt(width / 10), height / 10 + r.nextInt(height / 8))
        }
        g.dispose()
        return image
    }

    private fun jpeg(image: BufferedImage, quality: Float, scale: Double = 1.0): ByteArray {
        val w = (image.width * scale).toInt(); val h = (image.height * scale).toInt()
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { it.createGraphics().drawImage(image, 0, 0, w, h, null) }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality }
            writer.write(null, IIOImage(scaled, null, null), param)
        }
        return out.toByteArray()
    }

    private fun bits(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

    @Test
    fun `a photo saved again smaller prints as itself, another photo does not`() {
        val original = PhotoPrints.print(jpeg(photo(1), 0.95f))!!
        val copy = PhotoPrints.print(jpeg(photo(1), 0.5f, scale = 0.4))!!
        val other = PhotoPrints.print(jpeg(photo(2), 0.95f))!!
        assertTrue(bits(original, copy) <= 3, "copy ${bits(original, copy)} bits apart")
        assertTrue(bits(original, other) > 12, "other photo only ${bits(original, other)} bits apart")
    }

    /** A nearly flat picture: a soft left-to-right shade with [seed]'s small object somewhere in it. */
    private fun flat(seed: Int, width: Int = 640, height: Int = 480): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val r = java.util.Random(seed.toLong())
        for (x in 0 until width) for (y in 0 until height) {
            val v = 250 - x * 40 / width
            image.setRGB(x, y, Color(v, v, v).rgb)
        }
        image.createGraphics().apply {
            color = Color(r.nextInt(256), r.nextInt(256), r.nextInt(256))
            fillRect(r.nextInt(width - 60), r.nextInt(height - 40), 40, 25)
            dispose()
        }
        return image
    }

    @Test
    fun `two flat photos of different things are not taken for the same photo`() = runBlocking {
        val dir = File("build/test-data/photo-prints").apply { mkdirs() }
        fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }
        val ssd = ad("pp-flat-ssd", "Tempelhof", "Kai")
        val van = ad("pp-flat-van", "Köln", "Jo")
        PhotoPrints.record(ssd, listOf("local"), file("flat-ssd.jpg", jpeg(flat(1), 0.9f)))
        PhotoPrints.record(van, listOf("local"), file("flat-van.jpg", jpeg(flat(2), 0.9f)))
        assertEquals(emptyList(), PhotoPrints.copiesOf(van).map { it.listingId })
    }

    @Test
    fun `each image host is asked for its JPEG`() {
        assertEquals("https://img.kleinanzeigen.de/api/v1/prod-ads/images/b6/x?rule=\$_59.JPG",
            PhotoPrints.jpegAddress("https://img.kleinanzeigen.de/api/v1/prod-ads/images/b6/x?rule=\$_35.AUTO"))
        assertEquals("https://img.classistatic.de/api/v1/mo-prod/images/42/x?rule=mo-640.jpg",
            PhotoPrints.jpegAddress("https://img.classistatic.de/api/v1/mo-prod/images/42/x?rule=mo-1600"))
        assertEquals("https://prod.pictures.autoscout24.net/listing-images/a.jpg/250x188.jpg",
            PhotoPrints.jpegAddress("https://prod.pictures.autoscout24.net/listing-images/a.jpg/250x188.webp"))
        assertEquals("https://i.ebayimg.com/images/g/x/s-l500.jpg", PhotoPrints.jpegAddress("https://i.ebayimg.com/images/g/x/s-l500.webp"))
    }

    private fun ad(id: String, city: String, seller: String?) = Listing(
        id = "KLEINANZEIGEN:$id", platformId = PlatformId.KLEINANZEIGEN, externalId = id, url = "https://example.org/$id",
        title = "VW Crafter", price = Money(12_000_00, Currency.EUR), scrapedAt = Clock.System.now(),
        location = Location(city = city), seller = seller?.let { Seller(name = it) },
    )

    @Test
    fun `an ad showing a photo from an ad elsewhere is found, the same seller's other ad in town is not`() = runBlocking {
        val dir = File("build/test-data/photo-prints").apply { mkdirs() }
        fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }
        val van = photo(7)
        val source = ad("pp-src", "Gönnheim", "Hans")
        val copier = ad("pp-copy", "Körne", "Service-Outlet")
        val repost = ad("pp-repost", "Gönnheim", "Hans")
        PhotoPrints.record(source, listOf("local"), file("src.jpg", jpeg(van, 0.9f)))
        PhotoPrints.record(repost, listOf("local"), file("repost.jpg", jpeg(van, 0.8f, 0.8)))
        PhotoPrints.record(copier, listOf("local"), file("copy.jpg", jpeg(van, 0.6f, 0.5)))
        assertEquals(setOf("KLEINANZEIGEN:pp-src", "KLEINANZEIGEN:pp-repost"), PhotoPrints.copiesOf(copier).map { it.listingId }.toSet())
        assertEquals(listOf("KLEINANZEIGEN:pp-copy"), PhotoPrints.copiesOf(source).map { it.listingId })
        assertFalse(PhotoPrints.copiesOf(repost).any { it.listingId == "KLEINANZEIGEN:pp-src" })
    }

    @Test
    fun `a dealer's ad in a district and the same ad in its city are one place`() {
        // AP-Automobile's Crafter: Kleinanzeigen places it in Aubing by its postcode 81249, mobile.de
        // in München by name. Compared by their names they were two places, and the van read as a
        // likely scam made of someone else's photos.
        val aubing = PhotoPrints.Entry("KLEINANZEIGEN:3545414060", "https://ka/1", place = "Aubing", lat = 48.1549, lon = 11.4107)
        val munich = PhotoPrints.Entry("MOBILE_DE:51545473665504", "https://mobile/1", place = "München", seller = "AP-Automobile GmbH")
        val byName = mapOf("München" to (48.1345 to 11.571), "Köln" to (50.9375 to 6.9603))
        assertFalse(PhotoPrints.apart(aubing, munich) { byName[it.place] })
        assertTrue(PhotoPrints.apart(aubing, munich.copy(place = "Köln")) { byName[it.place] })
    }

    @Test
    fun `one dealer under two names at one place is not apart`() {
        val autoscout = PhotoPrints.Entry("AUTOSCOUT24:67e9cb14", "https://as/1", place = "Fellbach", lat = 48.81, lon = 9.27, seller = "Hahn Automobile GmbH + Co. KG")
        val mobile = PhotoPrints.Entry("MOBILE_DE:52283147360992", "https://mobile/1", place = "Fellbach", lat = 48.81, lon = 9.28, seller = "Nutzfahrzeug Zentrum Rems-Murr")
        assertFalse(PhotoPrints.apart(autoscout, mobile) { null })
        assertTrue(PhotoPrints.apart(autoscout.copy(lat = null, lon = null, place = null), mobile.copy(lat = null, lon = null, place = null)) { null })
    }

    @Test
    fun `a dealer's closing photos under its different vans are no copies, a van posted twice is`() {
        // Autohaus Seitz: two Memmingen Crafters share only their last photos, which a Kempten
        // Crafter of the same group ends with too.
        val banner = 0x0F0F_3C3C_5A5A_6969L
        fun own(n: Int, seed: Long) = List(n) { seed * 1_000_003L + it * 7_919L }
        val first = PhotoPrints.Entry("KLEINANZEIGEN:3476770115", "https://ka/1", prints = own(16, 1) + banner)
        val second = PhotoPrints.Entry("KLEINANZEIGEN:3476770238", "https://ka/2", prints = own(15, 2) + banner)
        val kempten = PhotoPrints.Entry("KLEINANZEIGEN:3549095646", "https://ka/3", prints = own(4, 3) + banner)
        val dealers = mapOf(first.id to "86544847", second.id to "86544847", kempten.id to "86544924")
        assertTrue(PhotoPrints.isTemplate(listOf(first, second, kempten)) { dealers[it.id] })
        // Without a dealer's account, a photo under two ads stays evidence.
        assertFalse(PhotoPrints.isTemplate(listOf(first, second, kempten)) { null })
        // One van posted twice shares its photos and says nothing about templates.
        val repost = first.copy(id = "KLEINANZEIGEN:9", prints = first.prints.drop(1))
        assertFalse(PhotoPrints.isTemplate(listOf(first, repost, kempten)) { if (it == kempten) "86544924" else "86544847" })
    }

    @Test
    fun `a place with the reader's distance attached is the town`() {
        assertEquals("Berlin", PhotoPrints.placeName("Berlin 524 km"))
        assertEquals("Halle (Saale)", PhotoPrints.placeName("Halle (Saale)"))
    }
}
