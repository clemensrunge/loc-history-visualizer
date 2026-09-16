pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "loc-history-visualizer"

include(":ctok-java")
project(":ctok-java").projectDir = file("vendor/ctok-java")
