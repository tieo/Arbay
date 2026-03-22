package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.WATCHES

object WatchesCatalog {
    val products = listOf(
        // Apple
        KnownProduct("Apple Watch Ultra 2", WATCHES, "Apple", "\"Apple Watch Ultra 2\" -band -armband -huelle", "MQDY3", tags = listOf("smartwatch", "fitness", "premium")),
        KnownProduct("Apple Watch Series 10 (46mm)", WATCHES, "Apple", "\"Apple Watch\" Series 10 46mm -band -armband", tags = listOf("smartwatch", "fitness")),
        KnownProduct("Apple Watch Series 10 (42mm)", WATCHES, "Apple", "\"Apple Watch\" Series 10 42mm -band -armband", tags = listOf("smartwatch", "fitness")),
        KnownProduct("Apple Watch SE (2023)", WATCHES, "Apple", "\"Apple Watch SE\" 2023 -band -armband", tags = listOf("smartwatch", "fitness", "budget")),
        // Samsung
        KnownProduct("Samsung Galaxy Watch Ultra", WATCHES, "Samsung", "Galaxy Watch Ultra -band -armband", "SM-L705", tags = listOf("smartwatch", "fitness", "premium")),
        KnownProduct("Samsung Galaxy Watch7 (44mm)", WATCHES, "Samsung", "Galaxy Watch7 44mm -band -armband", "SM-L505", tags = listOf("smartwatch", "fitness")),
        // Garmin
        KnownProduct("Garmin Fenix 8 AMOLED", WATCHES, "Garmin", "Garmin Fenix 8 AMOLED -band -armband -charger", tags = listOf("smartwatch", "fitness", "outdoor", "premium")),
        KnownProduct("Garmin Venu 3", WATCHES, "Garmin", "Garmin Venu 3 -band -armband -charger", "010-02784", tags = listOf("smartwatch", "fitness")),
        KnownProduct("Garmin Forerunner 965", WATCHES, "Garmin", "Garmin Forerunner 965 -band -armband -charger", "010-02809", tags = listOf("smartwatch", "running", "fitness")),
        // Google
        KnownProduct("Google Pixel Watch 2", WATCHES, "Google", "\"Pixel Watch 2\" -band -armband", tags = listOf("smartwatch", "fitness")),
    )
}
