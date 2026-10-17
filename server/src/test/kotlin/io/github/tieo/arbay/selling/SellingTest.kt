package io.github.tieo.arbay.selling

import io.github.tieo.arbay.crawler.KleinanzeigenDetailParser
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SellingTest {

    private val html = javaClass.getResource("/fixtures/kleinanzeigen_ad_copy.html")!!.readText()

    @Test
    fun copiesAnAdWithItsCategoryDetailsAndGalleryPhotosOnly() {
        val copy = KleinanzeigenDetailParser.adCopy(html)!!
        assertEquals("Testgerät 256 GB – guter Zustand", copy.title)
        assertEquals("173", copy.categoryId)
        assertEquals(mapOf("Marke" to "Google", "Speicher (GB)" to "256 GB", "Zustand" to "Gut"), copy.attributes)
        // The similar ads below the ad carry photos of their own, which are not the ad's.
        assertEquals(
            listOf(
                "https://img.kleinanzeigen.de/api/v1/prod-ads/images/aa/aaaaaaaa-1111-4111-8111-111111111111",
                "https://img.kleinanzeigen.de/api/v1/prod-ads/images/bb/bbbbbbbb-2222-4222-8222-222222222222",
            ),
            copy.photos,
        )
        assertEquals("Erste Zeile.\n\nZweiter Absatz mit Details.", copy.description)
    }

    @Test
    fun cropsBetweenFiveAndNinePercentOffEachSide() {
        val original = BufferedImage(1200, 1600, BufferedImage.TYPE_INT_RGB)
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(original, "jpeg", it) }.toByteArray()
        val one = ImageIO.read(Selling.cropALittle(jpeg, Random(1)).inputStream())
        val other = ImageIO.read(Selling.cropALittle(jpeg, Random(2)).inputStream())
        assertTrue(one.width in (1200 * 0.82).toInt()..(1200 * 0.90).toInt(), "width ${one.width}")
        assertTrue(one.height in (1600 * 0.82).toInt()..(1600 * 0.90).toInt(), "height ${one.height}")
        assertNotEquals(one.width to one.height, other.width to other.height)
    }

    /** A JPEG of [image] carrying an EXIF block with [orientation], as a phone camera writes it. */
    private fun jpegWithOrientation(image: BufferedImage, orientation: Int): ByteArray {
        val plain = ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
        val tiff = byteArrayOf(
            'I'.code.toByte(), 'I'.code.toByte(), 42, 0, 8, 0, 0, 0, // little-endian header, IFD at 8
            1, 0, // one entry
            0x12, 0x01, 3, 0, 1, 0, 0, 0, orientation.toByte(), 0, 0, 0, // Orientation, SHORT, 1 value
            0, 0, 0, 0, // no next IFD
        )
        val body = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = body.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + body
        return plain.copyOfRange(0, 2) + app1 + plain.copyOfRange(2, plain.size)
    }

    @Test
    fun readsTheOrientationAPhoneWrites() {
        val image = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
        (1..8).forEach { assertEquals(it, Selling.exifOrientation(jpegWithOrientation(image, it))) }
        assertEquals(1, Selling.exifOrientation(ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()))
    }

    @Test
    fun turnsEachOrientationUpright() {
        // Stored as a camera saw it: 3 wide, 2 high, every pixel its own colour.
        val stored = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 2) for (x in 0 until 3) stored.setRGB(x, y, y * 3 + x + 1)
        fun rows(i: BufferedImage) = (0 until i.height).map { y -> (0 until i.width).map { x -> i.getRGB(x, y) and 0xffffff } }
        assertEquals(listOf(listOf(4, 1), listOf(5, 2), listOf(6, 3)), rows(Selling.turned(stored, 6)))
        assertEquals(listOf(listOf(3, 6), listOf(2, 5), listOf(1, 4)), rows(Selling.turned(stored, 8)))
        assertEquals(listOf(listOf(6, 5, 4), listOf(3, 2, 1)), rows(Selling.turned(stored, 3)))
        assertEquals(listOf(listOf(1, 4), listOf(2, 5), listOf(3, 6)), rows(Selling.turned(stored, 5)))
        assertEquals(listOf(listOf(6, 3), listOf(5, 2), listOf(4, 1)), rows(Selling.turned(stored, 7)))
        assertEquals(listOf(listOf(3, 2, 1), listOf(6, 5, 4)), rows(Selling.turned(stored, 2)))
        assertEquals(listOf(listOf(4, 5, 6), listOf(1, 2, 3)), rows(Selling.turned(stored, 4)))
    }

    @Test
    fun keepsAnOwnPhotoUprightScaledAndWithoutItsMetadata() {
        val photo = jpegWithOrientation(BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB), 6)
        val kept = Selling.ownPhoto(photo)
        val image = ImageIO.read(kept.inputStream())
        assertEquals(Selling.MAX_PHOTO_SIDE * 3 / 4 to Selling.MAX_PHOTO_SIDE, image.width to image.height)
        assertEquals(1, Selling.exifOrientation(kept))
        assertTrue(!String(kept, Charsets.ISO_8859_1).contains("Exif"))
    }
}
