package com.postsaimanager.core.domain.skills

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ToolExchange
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ToolStepsTest {

    @Test
    @DisplayName("the stored calls of a reply become the steps of its progress panel, in order")
    fun `steps follow the calls`() {
        val steps = ToolSteps.of(
            listOf(
                ToolExchange("load_skill", """{"skill_name":"send-email"}""", """{"skill_name":"send-email","skill_instructions":"..."}"""),
                ToolExchange(
                    "run_intent",
                    """{"intent":"send_email","parameters":"{\"extra_email\":\"a@b.de\"}"}""",
                    """{"action":"send_email","status":"proposed to the user"}""",
                ),
                ToolExchange(
                    "run_js",
                    """{"skill_name":"text-spinner","script_name":"index.html","data":"{}"}""",
                    """{"result":"","status":"succeeded"}""",
                    JsSkillResults.shownJson(JsSkillWebview("text-spinner/assets/webview.html", 1.33f)),
                ),
            ),
        )

        assertThat(steps.map { it.kind }).containsExactly(ToolStepKind.LOAD_SKILL, ToolStepKind.RUN_INTENT, ToolStepKind.RUN_JS).inOrder()
        assertThat(steps[0].subject).isEqualTo("send-email")
        assertThat(steps[1].subject).isEqualTo("send_email")
        assertThat(steps[1].detail).contains("a@b.de")
        assertThat(steps[2].subject).isEqualTo("text-spinner")
        assertThat(steps[2].detail).isEqualTo("index.html")
        assertThat(steps[2].webview?.url).isEqualTo("text-spinner/assets/webview.html")
        assertThat(steps.none { it.failed }).isTrue()
    }

    @Test
    fun `a refused call is a failed step`() {
        val steps = ToolSteps.of(
            listOf(
                ToolExchange("load_skill", """{"skill_name":"nope"}""", """{"skill_name":"nope","skill_instructions":"Skill not found"}"""),
                ToolExchange("run_intent", """{"intent":"x","parameters":"{}"}""", """{"error":"bad","status":"failed"}"""),
                ToolExchange("run_js", """{"skill_name":"s","script_name":"i","data":""}""", """{"error":"boom","status":"failed"}"""),
            ),
        )
        assertThat(steps.map { it.failed }).containsExactly(true, true, true)
    }

    @Test
    fun `an unknown tool and unreadable json do not break the panel`() {
        val steps = ToolSteps.of(listOf(ToolExchange("future_tool", "not json", "")))
        assertThat(steps.single().kind).isEqualTo(ToolStepKind.OTHER)
        assertThat(steps.single().subject).isEqualTo("future_tool")
    }
}
