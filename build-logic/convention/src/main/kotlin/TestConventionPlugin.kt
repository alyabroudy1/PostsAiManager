import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/**
 * Standard unit-test setup for every module.
 *
 * Exists because JUnit 5 was declared across the project but never activated: without
 * `useJUnitPlatform()`, AGP's default JUnit 4 runner discovers **zero** JUnit 5 tests and
 * reports the task green. `:core:data` was patched by hand; every other module was still
 * a trap, so this plugin makes the setup impossible to forget.
 *
 * Configures Gradle `Test` tasks directly rather than AGP's `testOptions`, so the plugin
 * needs no dependency on the Android Gradle Plugin — AGP's unit-test tasks are ordinary
 * `Test` tasks.
 */
class TestConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
                exceptionFormat = TestExceptionFormat.FULL
                showStackTraces = true
            }
        }

        // `testImplementation` only exists once an Android/Java plugin has been applied.
        listOf(
            "com.android.library",
            "com.android.application",
            "org.jetbrains.kotlin.jvm",
            "java-library",
        ).forEach { pluginId ->
            target.pluginManager.withPlugin(pluginId) {
                target.addTestDependencies()
            }
        }
    }

    private fun Project.addTestDependencies() {
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        fun lib(alias: String) = libs.findLibrary(alias).orElseThrow {
            IllegalStateException("Version catalog is missing library alias '$alias'")
        }

        dependencies.add("testImplementation", lib("junit5-api"))
        dependencies.add("testRuntimeOnly", lib("junit5-engine"))
        dependencies.add("testImplementation", lib("truth"))
        dependencies.add("testImplementation", lib("mockk"))
        dependencies.add("testImplementation", lib("coroutines-test"))
        dependencies.add("testImplementation", lib("turbine"))

        // Shared fakes and fixtures — but :core:testing cannot depend on itself.
        if (path != ":core:testing") {
            dependencies.add("testImplementation", project(":core:testing"))
        }
    }
}
