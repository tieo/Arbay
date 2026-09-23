import java.util.Properties

// The installable Android app. Everything it shows lives in composeApp; this module is what makes
// it an app: the application id, the manifest, the launcher icons, and the secrets baked in.
plugins {
    alias(libs.plugins.androidApplication)
}

// Server URL + non-interactive auth header live in a gitignored secret.properties
// (see secret.properties.example). Baked into BuildConfig so the app needs no manual
// sign-in; an absent file falls back to the localhost/LAN default and no auth.
val arbaySecrets = Properties().apply {
    val f = rootProject.file("secret.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun arbaySecret(key: String): String = arbaySecrets.getProperty(key).orEmpty()

android {
    namespace = "io.github.tieo.arbay.app"
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
    implementation(projects.composeApp)
    debugImplementation(libs.compose.uiTooling)
}
