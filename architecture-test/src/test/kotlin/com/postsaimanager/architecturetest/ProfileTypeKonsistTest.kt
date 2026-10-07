package com.postsaimanager.architecturetest

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Household and contacts (plans/14): a profile is described by two things, what it is (`ProfileKind`) and how it belongs to the
 * household (`HouseholdRole`). The one-axis `ProfileType` is only kept readable for one version, mapped 1:1, until it is dropped.
 * New presentation code uses kind and role, so `ProfileType` never spreads into the features again.
 *
 * [ALLOWED] names the feature files that may still mention it. It is empty: the profiles and documents screens were the last users
 * and moved to kind and role. Add a reasoned entry (the exact file) rather than widening the rule.
 */
class ProfileTypeKonsistTest {

    private val ALLOWED = emptySet<String>()

    private fun KoFileDeclaration.normalizedPath(): String = projectPath.replace('\\', '/')

    /** Everything under `feature/<module>/src/main`: presentation source, not its tests. */
    private fun KoFileDeclaration.isFeatureMainSource(): Boolean {
        val segments = normalizedPath().split('/')
        val featureIndex = segments.indexOf("feature")
        return featureIndex >= 0 &&
            segments.getOrNull(featureIndex + 2) == "src" &&
            segments.getOrNull(featureIndex + 3) == "main"
    }

    @Test
    fun `feature code describes a profile by kind and household role, never by the legacy ProfileType`() {
        val violations = Konsist.scopeFromProject()
            .files
            .filter { it.isFeatureMainSource() }
            .filter { file -> ALLOWED.none { file.normalizedPath().endsWith(it) } }
            .filter { it.imports.any { import -> import.name.endsWith(".ProfileType") } || Regex("\\bProfileType\\b").containsMatchIn(it.text) }
            .map { it.normalizedPath() }

        assertTrue(violations.isEmpty()) {
            "These feature files use the legacy ProfileType; use ProfileKind and HouseholdRole instead " +
                "(or add a reasoned entry to ALLOWED in ProfileTypeKonsistTest):\n" + violations.joinToString("\n") { "  $it" }
        }
    }
}
