plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinSerialization)
    application
}

group = "io.github.tieo.arbay"
version = "1.0.0"
application {
    mainClass.set("io.github.tieo.arbay.ApplicationKt")
    
    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

dependencies {
    implementation(projects.shared)
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serverSse)
    implementation(libs.ktor.serverBodyLimit)
    implementation(libs.ktor.serializationJson)
    implementation(libs.ktor.clientCioJvm)
    implementation(libs.ktor.clientContentNegotiationJvm)
    implementation(libs.jsoup)
    // Jsoup marks its API with these but leaves them out of its own dependencies; without them
    // Kotlin cannot read which of its returns may be null.
    compileOnly(libs.jspecify)
    implementation(libs.playwright)
    implementation(libs.onnxruntime)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientMock)
    testImplementation(libs.kotlin.testJunit)
}

// Image preprocessing (CLIP) uses java.awt; run headless so it needs no X11 libs.
// Tests write into a directory of their own that starts empty on every run, never into the
// ~/.arbay of the machine running them. The downloaded models are a cache, so they stay shared.
tasks.withType<Test> {
    systemProperty("java.awt.headless", "true")
    val testData = layout.buildDirectory.dir("test-data").get().asFile
    systemProperty("arbay.dataDir", testData.absolutePath)
    systemProperty("arbay.modelsDir", File(System.getProperty("user.home"), ".arbay/models").absolutePath)
    doFirst { testData.deleteRecursively(); testData.mkdirs() }
}
// Probes print what a model or a market does, for a person to read; they assert nothing and load
// real models, so they stay out of the test run and are asked for by name:
// ./gradlew :server:probes
tasks.named<Test>("test") {
    filter { excludeTestsMatching("*Probe") }
}
tasks.register<Test>("probes") {
    group = "verification"
    description = "Run the probes that print model and market behaviour"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("*Probe") }
    testLogging { showStandardStreams = true }
}
// Prints every market's declared capabilities as JSON, which the model site renders its market
// objects from. Run: ./gradlew :server:dumpCapabilities -q
tasks.register<JavaExec>("dumpCapabilities") {
    group = "documentation"
    description = "Print what each market can do, as its crawler declares it"
    mainClass.set("io.github.tieo.arbay.tools.CapabilityDumpKt")
    classpath = sourceSets["main"].runtimeClasspath
}
