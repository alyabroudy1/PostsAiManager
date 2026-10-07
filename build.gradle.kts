// Top-level build file

// The R8 that AGP 8.7.3 bundles predates Kotlin 2.4 metadata (release builds print "error parsing kotlin metadata" for every class).
// A newer R8 on the buildscript classpath is the documented way to use another one; the version is in the catalogue.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.android.r8)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

// Dagger/Hilt 2.58 (the last release that runs on AGP 8) bundles kotlin-metadata-jvm 2.3, which cannot read the Kotlin 2.4
// metadata this project and LiteRT-LM produce. Every annotation-processor classpath gets the library at the Kotlin version.
allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" && requested.name == "kotlin-metadata-jvm") {
                useVersion(libs.versions.kotlin.get())
            }
        }
    }
}
