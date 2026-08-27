package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.HEADPHONES

object HeadphonesCatalog {
    val products = listOf(
        KnownProduct("Sony WH-1000XM5", HEADPHONES, "Sony", "WH-1000XM5", "WH1000XM5/B", listOf("4548736132597"), tags = listOf("anc", "over-ear", "bluetooth"), excludeKeywords = listOf("case", "cover", "pad")),
        KnownProduct("Sony WH-1000XM4", HEADPHONES, "Sony", "WH-1000XM4", "WH1000XM4/B", listOf("4548736112162"), tags = listOf("anc", "over-ear", "bluetooth"), excludeKeywords = listOf("case", "cover", "pad")),
        KnownProduct("Sony WH-1000XM3", HEADPHONES, "Sony", "WH-1000XM3", "WH1000XM3/B", listOf("4548736081253"), tags = listOf("anc", "over-ear", "bluetooth"), excludeKeywords = listOf("case", "cover")),
        KnownProduct("Sony WF-1000XM5", HEADPHONES, "Sony", "WF-1000XM5", "WF1000XM5/B", listOf("4548736146068"), tags = listOf("anc", "in-ear", "bluetooth", "tws"), excludeKeywords = listOf("case", "tip")),
        KnownProduct("Sony WF-1000XM4", HEADPHONES, "Sony", "WF-1000XM4", "WF1000XM4/B", listOf("4548736130937"), tags = listOf("anc", "in-ear", "bluetooth", "tws"), excludeKeywords = listOf("case", "tip")),
        KnownProduct("Apple AirPods Pro 2 (USB-C)", HEADPHONES, "Apple", "AirPods Pro 2 USB-C", "MTJV3ZM/A", listOf("0194253940241"), tags = listOf("anc", "in-ear", "bluetooth", "tws"), excludeKeywords = listOf("case", "strap")),
        KnownProduct("Apple AirPods Max", HEADPHONES, "Apple", "AirPods Max", "MGYH3ZM/A", listOf("0194252049082"), tags = listOf("anc", "over-ear", "bluetooth"), excludeKeywords = listOf("case", "cover")),
        KnownProduct("Apple AirPods 3", HEADPHONES, "Apple", "AirPods 3", "MPNY3ZM/A", listOf("0194253324034"), tags = listOf("in-ear", "bluetooth", "tws"), excludeKeywords = listOf("case", "strap")),
        KnownProduct("Bose QuietComfort Ultra Headphones", HEADPHONES, "Bose", "Bose QuietComfort Ultra headphones", "880066-0100", listOf("0017817847575"), tags = listOf("anc", "over-ear", "bluetooth"), excludeKeywords = listOf("case")),
        KnownProduct("Bose QuietComfort 45", HEADPHONES, "Bose", "Bose QC45", "866724-0100", listOf("0017817834988"), tags = listOf("anc", "over-ear", "bluetooth"), aliases = listOf("QuietComfort 45"), excludeKeywords = listOf("case")),
        KnownProduct("Bose QuietComfort Ultra Earbuds", HEADPHONES, "Bose", "Bose QuietComfort Ultra earbuds", "882826-0010", listOf("0017817847612"), tags = listOf("anc", "in-ear", "bluetooth", "tws"), excludeKeywords = listOf("case", "tip")),
        KnownProduct("Sennheiser Momentum 4 Wireless", HEADPHONES, "Sennheiser", "Sennheiser Momentum 4", "509266", listOf("4044155260045"), tags = listOf("anc", "over-ear", "bluetooth"), excludeKeywords = listOf("case", "cable")),
        KnownProduct("Sennheiser HD 660S2", HEADPHONES, "Sennheiser", "Sennheiser HD 660S2", "700091", listOf("4044155280838"), tags = listOf("open-back", "over-ear", "wired", "audiophile"), excludeKeywords = listOf("cable", "pad")),
        KnownProduct("Beyerdynamic DT 770 Pro", HEADPHONES, "Beyerdynamic", "Beyerdynamic DT 770 Pro", "459046", listOf("4010118459047"), tags = listOf("closed-back", "over-ear", "wired", "studio"), excludeKeywords = listOf("pad", "cable")),
        KnownProduct("Beyerdynamic DT 990 Pro", HEADPHONES, "Beyerdynamic", "Beyerdynamic DT 990 Pro", "459038", listOf("4010118459030"), tags = listOf("open-back", "over-ear", "wired", "studio"), excludeKeywords = listOf("pad", "cable")),
        KnownProduct("Samsung Galaxy Buds2 Pro", HEADPHONES, "Samsung", "Galaxy Buds2 Pro", "SM-R510", listOf("8806094544060"), tags = listOf("anc", "in-ear", "bluetooth", "tws"), excludeKeywords = listOf("case", "tip")),
        KnownProduct("Samsung Galaxy Buds FE", HEADPHONES, "Samsung", "Galaxy Buds FE", "SM-R400", listOf("8806095073446"), tags = listOf("anc", "in-ear", "bluetooth", "tws"), excludeKeywords = listOf("case", "tip")),
        KnownProduct("JBL Tune 770NC", HEADPHONES, "JBL", "JBL Tune 770NC", "JBLT770NC", listOf("6925281972645"), tags = listOf("anc", "over-ear", "bluetooth"), excludeKeywords = listOf("case")),
        KnownProduct("Google Pixel Buds Pro 2", HEADPHONES, "Google", "Pixel Buds Pro 2", "GA05275", tags = listOf("anc", "in-ear", "bluetooth", "tws"), excludeKeywords = listOf("case", "tip")),
        KnownProduct("Audio-Technica ATH-M50x", HEADPHONES, "Audio-Technica", "Audio-Technica ATH-M50x", "ATH-M50x", listOf("4961310125431"), tags = listOf("closed-back", "over-ear", "wired", "studio"), excludeKeywords = listOf("pad", "cable")),
    )
}
