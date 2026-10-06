package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.WATCHES

object WatchesCatalog {
    val products = listOf(
        // Apple
        KnownProduct("Apple Watch Ultra 2", WATCHES, "Apple", "Apple Watch Ultra 2", "MQDY3", tags = listOf("smartwatch", "fitness", "premium"), excludeKeywords = listOf("band", "armband", "huelle")),
        KnownProduct("Apple Watch Series 10 (46mm)", WATCHES, "Apple", "Apple Watch\" Series 10 46mm", tags = listOf("smartwatch", "fitness"), excludeKeywords = listOf("band", "armband")),
        KnownProduct("Apple Watch Series 10 (42mm)", WATCHES, "Apple", "Apple Watch\" Series 10 42mm", tags = listOf("smartwatch", "fitness"), excludeKeywords = listOf("band", "armband")),
        KnownProduct("Apple Watch SE (2023)", WATCHES, "Apple", "Apple Watch SE\" 2023", tags = listOf("smartwatch", "fitness", "budget"), excludeKeywords = listOf("band", "armband")),
        // Samsung
        KnownProduct("Samsung Galaxy Watch Ultra", WATCHES, "Samsung", "Galaxy Watch Ultra", "SM-L705", tags = listOf("smartwatch", "fitness", "premium"), excludeKeywords = listOf("band", "armband")),
        KnownProduct("Samsung Galaxy Watch7 (44mm)", WATCHES, "Samsung", "Galaxy Watch7 44mm", "SM-L505", tags = listOf("smartwatch", "fitness"), excludeKeywords = listOf("band", "armband")),
        // Garmin
        KnownProduct("Garmin Fenix 8 AMOLED", WATCHES, "Garmin", "Garmin Fenix 8 AMOLED", tags = listOf("smartwatch", "fitness", "outdoor", "premium"), excludeKeywords = listOf("band", "armband", "charger")),
        KnownProduct("Garmin Venu 3", WATCHES, "Garmin", "Garmin Venu 3", "010-02784", tags = listOf("smartwatch", "fitness"), excludeKeywords = listOf("band", "armband", "charger")),
        KnownProduct("Garmin Forerunner 965", WATCHES, "Garmin", "Garmin Forerunner 965", "010-02809", tags = listOf("smartwatch", "running", "fitness"), excludeKeywords = listOf("band", "armband", "charger")),
        // Google
        KnownProduct("Google Pixel Watch 2", WATCHES, "Google", "Pixel Watch 2", tags = listOf("smartwatch", "fitness"), excludeKeywords = listOf("band", "armband")),
    )
}
