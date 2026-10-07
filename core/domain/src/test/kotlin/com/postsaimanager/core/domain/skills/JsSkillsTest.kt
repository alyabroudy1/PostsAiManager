package com.postsaimanager.core.domain.skills

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class JsSkillsTest {

    @Test
    @DisplayName("a script that answers a JSON object with result, error or webview is structured")
    fun `structured answers`() {
        val ok = JsSkillResults.parse("""{"result":"abc123"}""")
        assertThat(ok.structured).isTrue()
        assertThat(ok.result).isEqualTo("abc123")
        assertThat(ok.error).isNull()

        val failed = JsSkillResults.parse("""{"error":"Failed to calculate hash"}""")
        assertThat(failed.error).isEqualTo("Failed to calculate hash")

        val view = JsSkillResults.parse("""{"webview":{"url":"webview.html?label=x","aspectRatio":1.5}}""")
        assertThat(view.webview).isEqualTo(JsSkillWebview("webview.html?label=x", 1.5f))
    }

    @Test
    fun `a webview without an aspect ratio is 4 by 3, as in the Gallery`() {
        val view = JsSkillResults.parse("""{"webview":{"url":"a.html"}}""")
        assertThat(view.webview?.aspectRatio).isEqualTo(JsSkillResults.DEFAULT_ASPECT_RATIO)
    }

    @Test
    fun `anything else is the result as it is`() {
        val plain = JsSkillResults.parse("just text")
        assertThat(plain.structured).isFalse()
        assertThat(plain.result).isEqualTo("just text")
        // A JSON object the contract does not know is plain text too.
        assertThat(JsSkillResults.parse("""{"other":1}""").structured).isFalse()
    }

    @Test
    fun `the webview is stored beside the call and read back`() {
        val webview = JsSkillWebview("text-spinner/assets/webview.html?label=hi", 1.25f)
        assertThat(JsSkillResults.webviewOfShown(JsSkillResults.shownJson(webview))).isEqualTo(webview)
        assertThat(JsSkillResults.webviewOfShown("")).isNull()
        assertThat(JsSkillResults.webviewOfShown("not json")).isNull()
    }

    @Test
    @DisplayName("a script is a file of the skill's own scripts folder; nothing can leave the skill")
    fun `script paths stay inside the skill`() {
        assertThat(JsSkillPaths.script("calculate-hash", "index.html")).isEqualTo("calculate-hash/scripts/index.html")
        assertThat(JsSkillPaths.script("calculate-hash", "../../other/SKILL.md")).isNull()
        assertThat(JsSkillPaths.script("calculate-hash", "/etc/passwd")).isNull()
        assertThat(JsSkillPaths.script("calculate-hash", "a%2e%2e/b")).isNull()
        assertThat(JsSkillPaths.script("../other", "index.html")).isNull()
        assertThat(JsSkillPaths.script("a/b", "index.html")).isNull()
    }

    @Test
    fun `a webview url is a file of the skill's assets folder, never an address`() {
        assertThat(JsSkillPaths.webview("text-spinner", "webview.html?label=x")).isEqualTo("text-spinner/assets/webview.html?label=x")
        assertThat(JsSkillPaths.webview("text-spinner", "https://example.com/x")).isNull()
        assertThat(JsSkillPaths.webview("text-spinner", "//example.com/x")).isNull()
        assertThat(JsSkillPaths.webview("text-spinner", "file:///data/x")).isNull()
        assertThat(JsSkillPaths.webview("text-spinner", "../scripts/index.html")).isNull()
        assertThat(JsSkillPaths.webview("text-spinner", "")).isNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the relay runs each request and delivers its answer under the request id`() = runTest {
        val requests = MutableSharedFlow<JsSkillRequest>(extraBufferCapacity = 4)
        val delivered = mutableListOf<Pair<String, String>>()
        val executor = object : JsSkillExecutor {
            override suspend fun run(request: JsSkillRequest): String =
                if (request.scriptName == "boom") error("no sandbox") else """{"result":"${request.data}"}"""
        }
        val relay = RelayJsSkillsUseCase(requests, executor) { id, result -> delivered += id to result }
        val job = launch { relay.collect(this) }
        advanceUntilIdle()

        requests.emit(JsSkillRequest("1", "calculate-hash", "index.html", "x"))
        requests.emit(JsSkillRequest("2", "calculate-hash", "boom", "y"))
        advanceUntilIdle()

        assertThat(delivered.first { it.first == "1" }.second).isEqualTo("""{"result":"x"}""")
        // A sandbox that fails still answers: the reply waiting for it must not hang.
        assertThat(JsSkillResults.parse(delivered.first { it.first == "2" }.second).error).contains("no sandbox")
        job.cancel()
    }
}
