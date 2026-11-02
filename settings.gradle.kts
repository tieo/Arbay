rootProject.name = "Arbay"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":androidApp")
include(":client")
include(":composeApp")
include(":webApp")
include(":server")
include(":shared")
// The repository's own git hooks (.githooks) stop a commit or a push that would publish something
// identifying the machine or its owner. A clone does not carry git configuration, so the first
// build switches them on wherever no hooks path is set yet. A machine that sets one globally is
// left alone: its own hooks are expected to hand over to .githooks, as the dotfiles' do.
run {
    val configured = providers.exec {
        commandLine("git", "config", "core.hooksPath")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    if (configured.isEmpty() && file(".git").exists()) {
        providers.exec { commandLine("git", "config", "core.hooksPath", ".githooks") }.result.get()
    }
}

// A shared build cache where the machine names one (GRADLE_CACHE_URL), so a task one machine built
// is fetched by the next instead of built again. Pushing needs the cache's password; without the
// variables the build runs on its local cache alone, so no host is named here.
buildCache {
    remote<HttpBuildCache> {
        val url = System.getenv("GRADLE_CACHE_URL") ?: return@remote
        this.url = uri(url)
        isEnabled = true
        isAllowInsecureProtocol = true
        isPush = System.getenv("GRADLE_CACHE_PASSWORD") != null
        credentials {
            username = System.getenv("GRADLE_CACHE_USER")
            password = System.getenv("GRADLE_CACHE_PASSWORD")
        }
    }
}
