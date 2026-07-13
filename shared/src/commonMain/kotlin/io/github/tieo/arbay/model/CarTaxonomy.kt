package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/**
 * Canonical car makes and models the app offers in its picker. The canonical id is a
 * lowercase-hyphen slug (AutoScout24/Otomoto path style); [platformSlugs] holds a per-site
 * override keyed by [PlatformId.name] for sites whose token differs, empty when the
 * canonical id is used as-is.
 *
 * The bundled [CarTaxonomySeed] ships in the app so the picker works offline and on first
 * launch. The server serves a version of this that a daily job refreshes from each site's
 * own filter catalog, and the app pulls that when its cached [version] is stale.
 */
@Serializable
data class CarTaxonomy(
    val version: String,
    val makes: List<CarMakeNode>,
)

@Serializable
data class CarMakeNode(
    val id: String,
    val name: String,
    val models: List<CarModelNode> = emptyList(),
    val platformSlugs: Map<String, String> = emptyMap(),
)

@Serializable
data class CarModelNode(
    val id: String,
    val name: String,
    val platformSlugs: Map<String, String> = emptyMap(),
)

/** Baseline taxonomy bundled in the app. Replaced at runtime by the server's synced copy. */
object CarTaxonomySeed {

    val taxonomy: CarTaxonomy = CarTaxonomy(version = "seed-1", makes = makes())

    private fun models(vararg names: String): List<CarModelNode> =
        names.map { CarModelNode(id = slug(it), name = it) }

    private fun slug(name: String): String =
        name.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    private fun makes(): List<CarMakeNode> = listOf(
        CarMakeNode("volkswagen", "Volkswagen", models(
            "Golf", "Polo", "Passat", "Tiguan", "T-Roc", "T-Cross", "Touran", "Arteon",
            "Up", "ID.3", "ID.4", "ID.5", "Caddy", "Crafter", "Transporter", "Multivan", "Amarok",
        )),
        CarMakeNode("mercedes-benz", "Mercedes-Benz", models(
            "A-Klasse", "B-Klasse", "C-Klasse", "E-Klasse", "S-Klasse", "CLA", "GLA", "GLB",
            "GLC", "GLE", "Sprinter", "Vito", "V-Klasse", "Citan",
        )),
        CarMakeNode("bmw", "BMW", models(
            "1er", "2er", "3er", "4er", "5er", "6er", "7er", "X1", "X2", "X3", "X4", "X5",
            "i3", "i4", "iX",
        )),
        CarMakeNode("audi", "Audi", models(
            "A1", "A3", "A4", "A5", "A6", "A7", "A8", "Q2", "Q3", "Q4", "Q5", "Q7", "Q8",
            "e-tron", "TT",
        )),
        CarMakeNode("opel", "Opel", models(
            "Corsa", "Astra", "Insignia", "Mokka", "Crossland", "Grandland", "Combo", "Vivaro", "Movano",
        )),
        CarMakeNode("ford", "Ford", models(
            "Fiesta", "Focus", "Kuga", "Puma", "Mondeo", "Transit", "Transit Custom", "Ranger", "Mustang",
        )),
        CarMakeNode("skoda", "Škoda", models(
            "Fabia", "Octavia", "Superb", "Scala", "Kamiq", "Karoq", "Kodiaq", "Enyaq",
        )),
        CarMakeNode("seat", "SEAT", models("Ibiza", "Leon", "Arona", "Ateca", "Tarraco")),
        CarMakeNode("cupra", "Cupra", models("Formentor", "Leon", "Born", "Ateca")),
        CarMakeNode("renault", "Renault", models(
            "Clio", "Megane", "Captur", "Kadjar", "Scenic", "Kangoo", "Trafic", "Master",
        )),
        CarMakeNode("peugeot", "Peugeot", models("208", "308", "2008", "3008", "5008", "Partner", "Boxer", "Expert")),
        CarMakeNode("citroen", "Citroën", models("C3", "C4", "C5 Aircross", "Berlingo", "Jumpy", "Jumper")),
        CarMakeNode("fiat", "Fiat", models("500", "Panda", "Tipo", "Ducato", "Doblo", "Talento")),
        CarMakeNode("toyota", "Toyota", models("Aygo", "Yaris", "Corolla", "C-HR", "RAV4", "Proace")),
        CarMakeNode("hyundai", "Hyundai", models("i10", "i20", "i30", "Kona", "Tucson", "Santa Fe", "Ioniq 5")),
        CarMakeNode("kia", "Kia", models("Picanto", "Rio", "Ceed", "Sportage", "Sorento", "Niro", "EV6")),
        CarMakeNode("mazda", "Mazda", models("2", "3", "6", "CX-3", "CX-30", "CX-5", "MX-5")),
        CarMakeNode("nissan", "Nissan", models("Micra", "Juke", "Qashqai", "X-Trail", "Leaf", "NV200")),
        CarMakeNode("volvo", "Volvo", models("V40", "V60", "V90", "XC40", "XC60", "XC90")),
        CarMakeNode("porsche", "Porsche", models("911", "Cayenne", "Macan", "Panamera", "Taycan", "718")),
        CarMakeNode("mini", "MINI", models("Cooper", "Countryman", "Clubman")),
        CarMakeNode("dacia", "Dacia", models("Sandero", "Duster", "Logan", "Jogger", "Spring")),
        CarMakeNode("suzuki", "Suzuki", models("Swift", "Vitara", "S-Cross", "Ignis", "Jimny")),
        CarMakeNode("mitsubishi", "Mitsubishi", models("Space Star", "ASX", "Eclipse Cross", "Outlander")),
        CarMakeNode("honda", "Honda", models("Jazz", "Civic", "CR-V", "HR-V")),
        CarMakeNode("tesla", "Tesla", models("Model 3", "Model Y", "Model S", "Model X")),
        CarMakeNode("iveco", "Iveco", models("Daily")),
        CarMakeNode("man", "MAN", models("TGE")),
        CarMakeNode("smart", "smart", models("Fortwo", "Forfour")),
        CarMakeNode("jeep", "Jeep", models("Renegade", "Compass", "Wrangler", "Cherokee")),
        CarMakeNode("land-rover", "Land Rover", models("Defender", "Discovery", "Range Rover", "Range Rover Evoque")),
        CarMakeNode("jaguar", "Jaguar", models("XE", "XF", "E-Pace", "F-Pace", "I-Pace")),
        CarMakeNode("alfa-romeo", "Alfa Romeo", models("Giulietta", "Giulia", "Stelvio", "Tonale")),
        CarMakeNode("chevrolet", "Chevrolet", models("Spark", "Aveo", "Cruze", "Captiva")),
        CarMakeNode("subaru", "Subaru", models("Impreza", "XV", "Forester", "Outback")),
        CarMakeNode("lexus", "Lexus", models("CT", "IS", "NX", "RX", "UX")),
        CarMakeNode("polestar", "Polestar", models("2", "3", "4")),
        CarMakeNode("byd", "BYD", models("Atto 3", "Dolphin", "Seal", "Han", "Tang")),
        CarMakeNode("mg", "MG", models("MG3", "ZS", "HS", "MG4", "Marvel R")),
    )
}
