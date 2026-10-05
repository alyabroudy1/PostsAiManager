import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    // Applied without a version: the Kotlin Gradle Plugin is already on the build's
    // classpath (every Android module in this project applies `kotlin-android`, which
    // is the same jar). Requesting a version here too — even the matching one via
    // `alias(libs.plugins.kotlin.jvm)` — fails with "plugin is already on the classpath
    // with an unknown version, so compatibility cannot be checked."
    id("org.jetbrains.kotlin.jvm")
}

// Plain JVM module — no Android plugin. Konsist scans Kotlin *source text* (imports,
// package declarations, file paths) via PSI; it never needs the Android SDK, a
// manifest, or a compiled classpath for the modules it inspects. Keeping this module
// Android-free keeps `:architecture-test:test` fast and keeps the enforcement tool
// itself outside the graph it polices.
//
// `pam.test-conventions` is NOT applied here. It unconditionally adds
// `testImplementation(project(":core:testing"))` to every module it configures, and
// `:core:testing` is an Android library — its published variants are all
// `androidJvm`-attributed (debugRuntimeElements / releaseRuntimeElements / …). A plain
// `org.jetbrains.kotlin.jvm` module asks for a `jvm`-attributed variant, so Gradle's
// variant matching fails outright ("No matching variant of project :core:testing was
// found"). This module needs none of :core:testing's fakes anyway — it doesn't test
// production code, it scans it — so JUnit 5 is wired directly below instead.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = true
    }
}

dependencies {
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)

    // Konsist.scopeFromProject() walks up from this module to the Gradle root and
    // scans every Kotlin file in every module — it does not need a `project(...)`
    // dependency on the modules it inspects. That is the whole point: these tests
    // must keep working even for modules this one has no build dependency on.
    testImplementation(libs.konsist)
}
