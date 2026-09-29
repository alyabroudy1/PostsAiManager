plugins {
    `kotlin-dsl`
}

group = "com.postsaimanager.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

gradlePlugin {
    plugins {
        register("testConventions") {
            id = "pam.test-conventions"
            implementationClass = "TestConventionPlugin"
        }
    }
}
