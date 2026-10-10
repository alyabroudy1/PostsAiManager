plugins {
    id("pam.test-conventions")
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "com.postsaimanager.core.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        // MigrationTestHelper reads the exported schemas from assets.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
            // Room's generated DAO classes are full of lambdas. As invokedynamic, D8 turns each into a synthetic `Outer$N` class, and when
            // the outer class is dexed apart from its Kotlin-made `Outer$1` (the insert adapter) the names collide ("defined multiple
            // times", ProfileEventDao_Impl). Compiled as classes there is nothing for D8 to name.
            freeCompilerArgs.add("-Xlambdas=class")
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:domain"))

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Google Drive backup (authorization, restart after a restore)
    implementation(libs.play.services.auth)
    implementation(libs.process.phoenix)

    // Ktor (for future network calls)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // ML Kit
    implementation(libs.mlkit.document.scanner)
    implementation(libs.mlkit.text.recognition)

    // ML Kit Entity Extraction (the "Gemma reads the letter" trial's second candidate source): dates, money, IBAN, phone, e-mail and
    // address spans. About 8.4 MB of library (the language models are downloaded on demand, one per language).
    implementation(libs.mlkit.entity.extraction)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // AndroidX
    implementation(libs.core.ktx)
    // The per-app language (AppCompatDelegate.setApplicationLocales), also on API < 33.
    implementation(libs.appcompat)

    // WorkManager
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Testing
    testImplementation(libs.room.testing)

    // Real Room on the JVM: Robolectric hosts the in-memory database, the Vintage engine runs its JUnit 4 runner on the JUnit 5 platform.
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.junit.vintage.engine)

    // Instrumented migration tests (JUnit4 — the instrumentation runner is JUnit4-based,
    // independent of the JUnit 5 platform the convention plugin sets up for unit tests).
    androidTestImplementation(libs.room.testing)
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
