import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Server URL + non-interactive auth header live in a gitignored secret.properties
// (see secret.properties.example). Baked into BuildConfig so the app needs no manual
// sign-in; an absent file falls back to the localhost/LAN default and no auth.
val arbaySecrets = Properties().apply {
    val f = rootProject.file("secret.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun arbaySecret(key: String): String = arbaySecrets.getProperty(key).orEmpty()

plugins {
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=androidx.compose.ui.ExperimentalComposeUiApi")
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    
    jvm()
    
    js {
        browser()
        binaries.executable()
    }
    
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.clientOkhttp)
            implementation(libs.androidx.work.runtime)
        }
        commonMain.dependencies {
            implementation(libs.navigationevent.compose)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.backhandler)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.ktor.clientCore)
            implementation(libs.ktor.clientContentNegotiation)
            implementation(libs.ktor.clientSerialization)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(projects.shared)
        }
        // The other way of drawing a screen without a device: Robolectric renders the @Preview
        // functions in androidMain. Kept beside the off-screen renderer so the two can be timed.
        androidUnitTest.dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.roborazzi)
            implementation(libs.roborazzi.compose)
            implementation(libs.roborazzi.previewScannerSupport)
            implementation(libs.previewScanner.android)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.ktor.clientCio)
            implementation(libs.kotlinx.datetime)
        }
    }
}

android {
    namespace = "io.github.tieo.arbay"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.tieo.arbay"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "ARBAY_SERVER_URL", "\"${arbaySecret("ARBAY_SERVER_URL")}\"")
        buildConfigField("String", "ARBAY_AUTH", "\"${arbaySecret("ARBAY_AUTH")}\"")
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions.unitTests {
        isIncludeAndroidResources = true
        all { it.systemProperty("robolectric.graphicsMode", "NATIVE") }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "io.github.tieo.arbay.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "io.github.tieo.arbay"
            packageVersion = "1.0.0"
        }
    }
}

// Off-screen gallery renderer: dumps a PNG per view to build/gallery for design review.
configurations.named("jvmRuntimeClasspath") {
    resolutionStrategy { force("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2") }
}

tasks.register<JavaExec>("renderGallery") {
    group = "arbay"
    description = "Render every view to build/gallery/*.png"
    val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
    dependsOn(jvmMain.compileAllTaskName)
    classpath = jvmMain.output.allOutputs + configurations.getByName("jvmRuntimeClasspath")
    mainClass.set("io.github.tieo.arbay.gallery.GalleryKt")
    systemProperty("gallery.out", layout.buildDirectory.dir("gallery").get().asFile.absolutePath)
    // Draw one view, or one size, while iterating on it: -Ponly=results -Psizes=light
    (findProperty("only") as String?)?.let { systemProperty("gallery.only", it) }
    (findProperty("sizes") as String?)?.let { systemProperty("gallery.sizes", it) }
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
    environment("LD_LIBRARY_PATH", "/nix/store/fdqacryg2w9kiwb94c9rzfsyff4im8xj-libglvnd-1.7.0/lib:/nix/store/5m91jqg1526jzsahrgmd37k4ml3nc5l4-libx11-1.8.13/lib:/nix/store/fc1g44pg3i10wfzh3gb4m54pfgclsn76-libxcb-1.17.0/lib:/nix/store/2krkc90x3ch0mgkk48fxlglq14nqapdr-libxau-1.0.12/lib:/nix/store/yr83qw7bdfdxf5lb2xmfs70qb5hap0hj-libxdmcp-1.1.5/lib:/nix/store/bg6ms0vw071g1fdbx2my6bbzsk62p6vd-fontconfig-2.17.1-lib/lib:/nix/store/zr22ggqbv79yv4y4wv06r4grla9h59yx-freetype-2.14.2/lib:/nix/store/si4q3zks5mn5jhzzyri9hhd3cv789vlm-gcc-15.2.0-lib/lib:/nix/store/wrxyd3k2f4bmh52pr5rpdjxxsm5r2qxm-gcc-15.2.0-libgcc/lib")
}
