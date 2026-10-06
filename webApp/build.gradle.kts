// The browser app: Arbay as a web page made of real HTML, for a desktop browser. It draws with
// Compose HTML and takes everything else (server client, view models, stored choices, formatting,
// what a search asks) from the client module, the same as the phone app does.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "arbay.js"
            }
            testTask { enabled = false }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(projects.client)
            implementation(libs.compose.runtime)
            implementation(libs.compose.html.core)
            implementation(libs.kotlinx.browser)
        }
    }
}
