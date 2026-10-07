package com.postsaimanager.feature.chat.skills

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** What an offline skill page may load: its own scripts and assets, and nothing else. */
class SkillSandboxTest {

    private val folder = "calculate-hash"
    private val base = "https://appassets.androidplatform.net/skills"

    @Test
    @DisplayName("a file of the skill's scripts or assets folder is served from the bundled assets")
    fun `own files are allowed`() {
        assertThat(SkillSandbox.assetPath("$base/$folder/scripts/index.html", folder)).isEqualTo("skills/$folder/scripts/index.html")
        assertThat(SkillSandbox.assetPath("$base/$folder/scripts/index.js", folder)).isEqualTo("skills/$folder/scripts/index.js")
        assertThat(SkillSandbox.assetPath("$base/$folder/assets/view.html?label=x#top", folder)).isEqualTo("skills/$folder/assets/view.html")
        assertThat(SkillSandbox.assetPath(SkillSandbox.urlFor("$folder/scripts/index.html"), folder)).isNotNull()
    }

    @Test
    @DisplayName("no network: every address that is not the sandbox's own is blocked")
    fun `network addresses are blocked`() {
        listOf(
            "https://example.com/skills/$folder/scripts/index.js",
            "http://appassets.androidplatform.net/skills/$folder/scripts/index.html",
            "https://appassets.androidplatform.net:8443/skills/$folder/scripts/index.html",
            "https://user@appassets.androidplatform.net/skills/$folder/scripts/index.html",
            "https://appassets.androidplatform.net.evil.com/skills/$folder/scripts/index.html",
            "wss://appassets.androidplatform.net/skills/$folder/scripts/index.html",
            "ftp://appassets.androidplatform.net/skills/$folder/scripts/index.html",
            "data:text/html,hi",
            "javascript:alert(1)",
        ).forEach { assertThat(SkillSandbox.assetPath(it, folder)).isNull() }
    }

    @Test
    @DisplayName("no file access beyond the skill's folder: files, other skills, traversal and encoded tricks are blocked")
    fun `nothing outside the skill folder`() {
        listOf(
            "file:///data/data/com.postsaimanager/databases/pam.db",
            "content://com.postsaimanager.provider/x",
            "$base/other-skill/scripts/index.html",
            "$base/$folder/SKILL.md",
            "$base/$folder/scripts/../../other-skill/scripts/index.html",
            "$base/$folder/scripts/%2e%2e/%2e%2e/SKILL.md",
            "$base/$folder/scripts/..%2f..%2fSKILL.md",
            "$base/$folder/scripts//index.html",
            "$base/$folder",
            "$base/",
            "https://appassets.androidplatform.net/assets/skills/$folder/scripts/index.html",
            "https://appassets.androidplatform.net/$folder/scripts/index.html",
        ).forEach { assertThat(SkillSandbox.assetPath(it, folder)).isNull() }
    }

    @Test
    fun `the skill folder of an address is read from it`() {
        assertThat(SkillSandbox.skillFolderOf(SkillSandbox.urlFor("text-spinner/assets/webview.html?label=x"))).isEqualTo("text-spinner")
        assertThat(SkillSandbox.skillFolderOf("https://example.com/skills/a/b")).isNull()
        assertThat(SkillSandbox.skillFolderOf(SkillSandbox.urlFor("lonely"))).isNull()
    }

    @Test
    fun `files are served with their own media type`() {
        assertThat(SkillSandbox.mimeOf("skills/a/scripts/index.html")).isEqualTo("text/html")
        assertThat(SkillSandbox.mimeOf("skills/a/scripts/index.js")).isEqualTo("text/javascript")
        assertThat(SkillSandbox.mimeOf("skills/a/assets/x.css")).isEqualTo("text/css")
        assertThat(SkillSandbox.mimeOf("skills/a/assets/x.unknown")).isEqualTo("application/octet-stream")
    }

    @Test
    @DisplayName("the page is told to stay offline in its own terms too: own origin only, no frames, no objects, no forms")
    fun `content security policy`() {
        val csp = SkillSandbox.CONTENT_SECURITY_POLICY
        assertThat(csp).contains("default-src 'self'")
        assertThat(csp).contains("connect-src 'self'")
        assertThat(csp).contains("frame-src 'none'")
        assertThat(csp).contains("object-src 'none'")
        assertThat(csp).contains("form-action 'none'")
        assertThat(csp).doesNotContain("http:")
        assertThat(csp).doesNotContain("*")
    }
}
