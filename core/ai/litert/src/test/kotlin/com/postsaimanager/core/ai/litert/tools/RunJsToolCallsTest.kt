package com.postsaimanager.core.ai.litert.tools

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.skills.JsSkillRequest
import com.postsaimanager.core.domain.skills.JsSkillResults
import com.postsaimanager.core.domain.skills.Skill
import com.postsaimanager.core.domain.skills.SkillCatalog
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** `run_js`: the request leaves for the app, the answer comes back through the broker, the model hears only the result. */
class RunJsToolCallsTest {

    private val hash = Skill("calculate-hash", "Hash a text.", "Call run_js.", folder = "calculate-hash", scripts = listOf("index.html", "index.js"))
    private val spinner = Skill("text-spinner", "Spin text.", "Call run_js.", folder = "text-spinner", scripts = listOf("index.html"))
    private val plain = Skill("send-email", "Write an e-mail.", "Call run_intent.", folder = "send-email")

    private val catalog = object : SkillCatalog {
        override suspend fun skills(): List<Skill> = listOf(hash, spinner, plain)
    }

    private val context = ToolContext()
    private val broker = JsBroker()
    private val requests = mutableListOf<JsSkillRequest>()

    /** Answers a request the way the app process would: through the broker, from another thread. */
    private fun calls(timeoutMs: Long = 5_000, answer: (JsSkillRequest) -> String?) =
        AgentToolCalls(catalog, context, js = broker, jsTimeoutMs = timeoutMs, newRequestId = { "req-1" }).also {
            context.bind(
                documentId = null,
                sink = {},
                onRunJs = { request ->
                    requests += request
                    answer(request)?.let { result -> Thread { broker.deliver(request.id, result) }.start() }
                },
            )
        }

    @Test
    fun `run_js publishes the script request and returns the script's result`() {
        val calls = calls { """{"result":"a94a8fe5"}""" }

        val result = calls.runJs("calculate-hash", "index.html", """{"text":"abc"}""")

        assertThat(requests).containsExactly(JsSkillRequest("req-1", "calculate-hash", "index.html", """{"text":"abc"}"""))
        assertThat(result).containsExactly("result", "a94a8fe5", "status", "succeeded")
        assertThat(context.exchanges().single().name).isEqualTo("run_js")
    }

    @Test
    fun `no script name means index html and no data means an empty object, as in the Gallery`() {
        val calls = calls { """{"result":"ok"}""" }

        calls.runJs("Calculate_Hash", "", "")

        assertThat(requests.single().scriptName).isEqualTo("index.html")
        assertThat(requests.single().data).isEqualTo("{}")
    }

    @Test
    fun `a script's error goes back to the model as a failure`() {
        val calls = calls { """{"error":"Failed to calculate hash: x"}""" }

        val result = calls.runJs("calculate-hash", "index.html", "{}")

        assertThat(result).containsExactly("error", "Failed to calculate hash: x", "status", "failed")
    }

    @Test
    @DisplayName("a webview the script asks for is stored beside the call for the chat, and not told to the model")
    fun `webview is shown not told`() {
        val calls = calls { """{"webview":{"url":"webview.html?label=hi","aspectRatio":1.0}}""" }

        val result = calls.runJs("text-spinner", "index.html", """{"label":"hi"}""")

        assertThat(result["status"]).isEqualTo("succeeded")
        assertThat(result.values.joinToString()).doesNotContain("webview.html")
        val exchange = context.exchanges().single()
        assertThat(JsSkillResults.webviewOfShown(exchange.shownJson)?.url).isEqualTo("text-spinner/assets/webview.html?label=hi")
        assertThat(exchange.resultJson).doesNotContain("webview.html")
    }

    @Test
    fun `a webview at an address is dropped, a script cannot send the chat anywhere`() {
        val calls = calls { """{"webview":{"url":"https://example.com/"},"result":"x"}""" }

        calls.runJs("text-spinner", "index.html", "{}")

        assertThat(context.exchanges().single().shownJson).isEmpty()
    }

    @Test
    fun `an unknown skill, a skill without scripts and a script that is not there are refused without asking the app`() {
        val calls = calls { error("must not be asked") }

        assertThat(calls.runJs("nope", "index.html", "{}")["status"]).isEqualTo("failed")
        assertThat(calls.runJs("send-email", "index.html", "{}")["error"]).contains("no script")
        assertThat(calls.runJs("calculate-hash", "../../x", "{}")["error"]).contains("not found")
        assertThat(requests).isEmpty()
    }

    @Test
    fun `a script that never answers fails the call after the timeout and the reply goes on`() {
        val calls = calls(answer = { null }, timeoutMs = 50)

        val result = calls.runJs("calculate-hash", "index.html", "{}")

        assertThat(result["status"]).isEqualTo("failed")
        assertThat(result["error"]).contains("did not answer")
    }

    @Test
    fun `a late answer after the timeout is ignored`() {
        broker.deliver("unknown", "x")
        val waiting = broker.expect("a")
        broker.cancelAll()
        assertThat(waiting.isCancelled).isTrue()
        broker.deliver("a", "late")
    }
}
