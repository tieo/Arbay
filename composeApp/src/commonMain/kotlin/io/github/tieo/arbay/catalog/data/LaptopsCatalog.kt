package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.LAPTOPS

object LaptopsCatalog {
    val products = listOf(
        // Apple
        KnownProduct("MacBook Air M3 13\"", LAPTOPS, "Apple", "MacBook Air M3 13 -huelle -sleeve", "MRXN3", tags = listOf("ultrabook", "arm")),
        KnownProduct("MacBook Air M3 15\"", LAPTOPS, "Apple", "MacBook Air M3 15 -huelle -sleeve", "MRXP3", tags = listOf("ultrabook", "arm")),
        KnownProduct("MacBook Air M2 13\"", LAPTOPS, "Apple", "MacBook Air M2 13 -huelle -sleeve", "MLY33", tags = listOf("ultrabook", "arm")),
        KnownProduct("MacBook Pro 14\" M3 Pro", LAPTOPS, "Apple", "MacBook Pro 14 M3 Pro -huelle -sleeve", "MRX33", tags = listOf("workstation", "arm")),
        KnownProduct("MacBook Pro 16\" M3 Max", LAPTOPS, "Apple", "MacBook Pro 16 M3 Max -huelle -sleeve", "MRW13", tags = listOf("workstation", "arm")),
        // Lenovo
        KnownProduct("ThinkPad X1 Carbon Gen 12", LAPTOPS, "Lenovo", "ThinkPad X1 Carbon Gen 12 -sleeve -dock", "21KC", tags = listOf("ultrabook", "business")),
        KnownProduct("ThinkPad X1 Carbon Gen 11", LAPTOPS, "Lenovo", "ThinkPad X1 Carbon Gen 11 -sleeve -dock", "21HM", tags = listOf("ultrabook", "business")),
        KnownProduct("ThinkPad T14s Gen 5", LAPTOPS, "Lenovo", "ThinkPad T14s Gen 5 -sleeve -dock", "21MV", tags = listOf("ultrabook", "business")),
        KnownProduct("Lenovo Legion Pro 5 16\"", LAPTOPS, "Lenovo", "Legion Pro 5 16 -sleeve -bag", tags = listOf("gaming")),
        // Dell
        KnownProduct("Dell XPS 15 (2024)", LAPTOPS, "Dell", "Dell XPS 15 9530 OR 9540 -sleeve -dock", "9540", tags = listOf("ultrabook", "creator")),
        KnownProduct("Dell XPS 13 (2024)", LAPTOPS, "Dell", "Dell XPS 13 9340 -sleeve -dock", "9340", tags = listOf("ultrabook")),
        KnownProduct("Dell Precision 7680", LAPTOPS, "Dell", "Dell Precision 7680 -dock -sleeve", "7680", tags = listOf("workstation", "mobile-workstation")),
        // Framework
        KnownProduct("Framework Laptop 16", LAPTOPS, "Framework", "Framework Laptop 16 -sleeve", tags = listOf("modular", "repairable")),
        KnownProduct("Framework Laptop 13", LAPTOPS, "Framework", "Framework Laptop 13 -sleeve", tags = listOf("modular", "repairable", "ultrabook")),
        // ASUS
        KnownProduct("ASUS ROG Zephyrus G14 (2024)", LAPTOPS, "ASUS", "ROG Zephyrus G14 2024 -sleeve -bag", "GA403", tags = listOf("gaming", "ultrabook")),
        KnownProduct("ASUS ROG Strix G16 (2024)", LAPTOPS, "ASUS", "ROG Strix G16 2024 -sleeve -bag", "G614", tags = listOf("gaming")),
        // HP
        KnownProduct("HP Spectre x360 14\"", LAPTOPS, "HP", "HP Spectre x360 14 -sleeve -pen", tags = listOf("ultrabook", "2-in-1", "convertible")),
        // MSI
        KnownProduct("MSI Stealth 16 Studio", LAPTOPS, "MSI", "MSI Stealth 16 Studio -sleeve -bag", tags = listOf("gaming", "creator")),
        // Razer
        KnownProduct("Razer Blade 16 (2024)", LAPTOPS, "Razer", "Razer Blade 16 2024 -sleeve -skin", "RZ09-0510", tags = listOf("gaming", "premium")),
    )
}
