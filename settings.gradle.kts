@file:Suppress("UnstableApiUsage")

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
        // NewPipeExtractor (YouTube stream resolution) is only published on JitPack.
        maven("https://jitpack.io") {
            mavenContent { includeGroupAndSubgroups("com.github.TeamNewPipe") }
        }
    }
}

rootProject.name = "fellwater"
include(":app", ":protocol")
