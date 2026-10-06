import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Everything the app knows and does without drawing it: the server client, the view models, the
// device's stored choices and the formatting of what is shown. The Compose screens (composeApp)
// and the browser app (webApp) both draw from this, so what a search, a filter or a price means
// is written once.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidLibrary {
        namespace = "io.github.tieo.arbay.client"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    jvm()

    // Built for the browser; its tests run on Node, which needs no browser to drive.
    js {
        browser {
            testTask { enabled = false }
        }
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared)
            // Observable state (mutableStateOf) only; nothing here draws.
            api(libs.compose.runtime)
            api(libs.androidx.lifecycle.viewmodel)
            api(libs.ktor.clientCore)
            implementation(libs.ktor.clientContentNegotiation)
            implementation(libs.ktor.clientSerialization)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
        }
        androidMain.dependencies {
            implementation(libs.ktor.clientOkhttp)
            implementation(libs.androidx.core.ktx)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.clientCio)
        }
        jsMain.dependencies {
            implementation(libs.ktor.clientJs)
            implementation(libs.kotlinx.browser)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
