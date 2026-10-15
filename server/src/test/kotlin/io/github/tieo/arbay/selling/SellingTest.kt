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
}
