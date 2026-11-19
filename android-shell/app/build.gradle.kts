import java.io.File

plugins {
    id("com.android.application")
}

// The release key lives outside the repository. Its folder holds the PKCS12 keystore and a file
// with its password; NEUBUY_ANDROID_KEY_DIR points elsewhere when the default does not apply.
val keyDir = File(
    System.getenv("NEUBUY_ANDROID_KEY_DIR")
        ?: "${System.getProperty("user.home")}/.local/share/neubuy-android",
)
val keystoreFile = File(keyDir, "keystore")
val releasePassword = File(keyDir, "password").takeIf { it.exists() }?.readText()?.trim()

android {
    namespace = "it.bosler.neubuy"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.bosler.neubuy"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystoreFile.exists() && releasePassword != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = releasePassword
                keyAlias = "neubuy"
                keyPassword = releasePassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.7.4")
}
