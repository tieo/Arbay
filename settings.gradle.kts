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
include(":composeApp")
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
