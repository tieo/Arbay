package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.LAPTOPS

object LaptopsCatalog {
    val products = listOf(
        // Apple
        KnownProduct("MacBook Air M3 13\"", LAPTOPS, "Apple", "MacBook Air M3 13", "MRXN3", tags = listOf("ultrabook", "arm"), excludeKeywords = listOf("huelle", "sleeve")),
        KnownProduct("MacBook Air M3 15\"", LAPTOPS, "Apple", "MacBook Air M3 15", "MRXP3", tags = listOf("ultrabook", "arm"), excludeKeywords = listOf("huelle", "sleeve")),
        KnownProduct("MacBook Air M2 13\"", LAPTOPS, "Apple", "MacBook Air M2 13", "MLY33", tags = listOf("ultrabook", "arm"), excludeKeywords = listOf("huelle", "sleeve")),
        KnownProduct("MacBook Pro 14\" M3 Pro", LAPTOPS, "Apple", "MacBook Pro 14 M3 Pro", "MRX33", tags = listOf("workstation", "arm"), excludeKeywords = listOf("huelle", "sleeve")),
        KnownProduct("MacBook Pro 16\" M3 Max", LAPTOPS, "Apple", "MacBook Pro 16 M3 Max", "MRW13", tags = listOf("workstation", "arm"), excludeKeywords = listOf("huelle", "sleeve")),
        // Lenovo
        KnownProduct("ThinkPad X1 Carbon Gen 12", LAPTOPS, "Lenovo", "ThinkPad X1 Carbon Gen 12", "21KC", tags = listOf("ultrabook", "business"), excludeKeywords = listOf("sleeve", "dock")),
        KnownProduct("ThinkPad X1 Carbon Gen 11", LAPTOPS, "Lenovo", "ThinkPad X1 Carbon Gen 11", "21HM", tags = listOf("ultrabook", "business"), excludeKeywords = listOf("sleeve", "dock")),
        KnownProduct("ThinkPad T14s Gen 5", LAPTOPS, "Lenovo", "ThinkPad T14s Gen 5", "21MV", tags = listOf("ultrabook", "business"), excludeKeywords = listOf("sleeve", "dock")),
        KnownProduct("Lenovo Legion Pro 5 16\"", LAPTOPS, "Lenovo", "Legion Pro 5 16", tags = listOf("gaming"), excludeKeywords = listOf("sleeve", "bag")),
        // Dell
        KnownProduct("Dell XPS 15 (2024)", LAPTOPS, "Dell", "Dell XPS 15 9530", "9540", tags = listOf("ultrabook", "creator"), aliases = listOf("9540"), excludeKeywords = listOf("sleeve", "dock")),
        KnownProduct("Dell XPS 13 (2024)", LAPTOPS, "Dell", "Dell XPS 13 9340", "9340", tags = listOf("ultrabook"), excludeKeywords = listOf("sleeve", "dock")),
        KnownProduct("Dell Precision 7680", LAPTOPS, "Dell", "Dell Precision 7680", "7680", tags = listOf("workstation", "mobile-workstation"), excludeKeywords = listOf("dock", "sleeve")),
        // Framework
        KnownProduct("Framework Laptop 16", LAPTOPS, "Framework", "Framework Laptop 16", tags = listOf("modular", "repairable"), excludeKeywords = listOf("sleeve")),
        KnownProduct("Framework Laptop 13", LAPTOPS, "Framework", "Framework Laptop 13", tags = listOf("modular", "repairable", "ultrabook"), excludeKeywords = listOf("sleeve")),
        // ASUS
        KnownProduct("ASUS ROG Zephyrus G14 (2024)", LAPTOPS, "ASUS", "ROG Zephyrus G14 2024", "GA403", tags = listOf("gaming", "ultrabook"), excludeKeywords = listOf("sleeve", "bag")),
        KnownProduct("ASUS ROG Strix G16 (2024)", LAPTOPS, "ASUS", "ROG Strix G16 2024", "G614", tags = listOf("gaming"), excludeKeywords = listOf("sleeve", "bag")),
        // HP
        KnownProduct("HP Spectre x360 14\"", LAPTOPS, "HP", "HP Spectre x360 14", tags = listOf("ultrabook", "2-in-1", "convertible"), excludeKeywords = listOf("sleeve", "pen")),
        // MSI
        KnownProduct("MSI Stealth 16 Studio", LAPTOPS, "MSI", "MSI Stealth 16 Studio", tags = listOf("gaming", "creator"), excludeKeywords = listOf("sleeve", "bag")),
        // Razer
        KnownProduct("Razer Blade 16 (2024)", LAPTOPS, "Razer", "Razer Blade 16 2024", "RZ09-0510", tags = listOf("gaming", "premium"), excludeKeywords = listOf("sleeve", "skin")),
    )
}
