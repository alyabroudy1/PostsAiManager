package com.postsaimanager.core.domain.agent

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class ToolCallFormatTest {

    private val fill = ToolSpec(
        "fill_field", "Fills one field.",
        ToolParams.schema(
            ToolParams.string("field_id", "The field."),
            ToolParams.string("value", "The value."),
            ToolParams.enumString("source", "Where it comes from.", listOf("profile", "user")),
            ToolParams.string("person_id", "Whose.", required = false),
        ),
    )
    private val ask = ToolSpec(
        "ask_user", "Asks the user.",
        ToolParams.schema(ToolParams.string("question", "The question."), ToolParams.stringArray("chips", "Buttons.", required = false)),
    )
    private val read = ToolSpec("read_form", "Reads the form.", ToolParams.schema())
    private val tools = listOf(fill, ask, read)

    // ── Qwen3.5's native format (read from its chat template) ──

    private val qwen = QwenToolCallFormat()

    @Test
    fun `Qwen describes the tools as JSON in a tools block with the native call example`() {
        val text = qwen.describeTools(tools)

        assertThat(text).startsWith("# Tools\n\nYou have access to the following functions:\n\n<tools>\n{\"type\":\"function\",\"function\":{\"name\":\"fill_field\"")
        assertThat(text).contains("\n</tools>\n\nIf you choose to call a function ONLY reply in the following format with NO suffix:")
        assertThat(text).contains("<tool_call>\n<function=example_function_name>\n<parameter=example_parameter_1>\nvalue_1\n</parameter>")
        assertThat(text.lines().count { it.startsWith("{\"type\":\"function\"") }).isEqualTo(3)
    }

    @Test
    fun `Qwen writes a call as the template does, strings raw and the rest as JSON`() {
        val args = obj("question" to str("Für wen ist das Formular?"), "chips" to JsonArray(listOf(JsonPrimitive("Ahmad"), JsonPrimitive("Ich"))))

        assertThat(qwen.renderCall("ask_user", args, tools)).isEqualTo(
            "<tool_call>\n<function=ask_user>\n<parameter=question>\nFür wen ist das Formular?\n</parameter>\n" +
                "<parameter=chips>\n[\"Ahmad\",\"Ich\"]\n</parameter>\n</function>\n</tool_call>",
        )
        assertThat(qwen.renderCall("read_form", obj(), tools)).isEqualTo("<tool_call>\n<function=read_form>\n</function>\n</tool_call>")
        assertThat(qwen.renderResult("read_form", "{\"ok\":true}")).isEqualTo("<tool_response>\n{\"ok\":true}\n</tool_response>")
    }

    @Test
    fun `Qwen reads back what it writes`() {
        val args = obj("question" to str("Hat Ahmad Allergien?"), "chips" to JsonArray(listOf(JsonPrimitive("Ja"), JsonPrimitive("Nein"))))

        val parsed = qwen.parse(qwen.renderCall("ask_user", args, tools), tools)

        assertThat(parsed).isEqualTo(ParsedCall.Call("ask_user", args))
    }

    @Test
    fun `Qwen reads a call without the outer tag, with text before it and a multi-line value`() {
        val reply = "Let me ask.\n<function=fill_field>\n<parameter=field_id>\nf3\n</parameter>\n<parameter=value>\nline one\nline two\n</parameter>\n" +
            "<parameter=source>\nuser\n</parameter>\n</function>"

        val call = qwen.parse(reply, tools) as ParsedCall.Call

        assertThat(call.name).isEqualTo("fill_field")
        assertThat(call.args["value"]).isEqualTo(JsonPrimitive("line one\nline two"))
        assertThat(call.args["field_id"]).isEqualTo(JsonPrimitive("f3"))
    }

    @Test
    fun `Qwen refuses an unknown function, a missing function block and an array that is not one`() {
        assertThat(qwen.parse("<tool_call>\n<function=delete_all>\n</function>\n</tool_call>", tools)).isInstanceOf(ParsedCall.Invalid::class.java)
        assertThat(qwen.parse("I will just answer.", tools)).isInstanceOf(ParsedCall.Invalid::class.java)
        val bad = "<tool_call>\n<function=ask_user>\n<parameter=question>\nq\n</parameter>\n<parameter=chips>\nnot an array\n</parameter>\n</function>\n</tool_call>"
        assertThat(qwen.parse(bad, tools)).isInstanceOf(ParsedCall.Invalid::class.java)
    }

    @Test
    fun `the Qwen grammar is built from the schemas (golden)`() {
        val grammar = qwen.grammar(listOf(fill, read))

        assertThat(grammar).isEqualTo(
            """
            root ::= "<tool_call>\n" call "\n</tool_call>"
            call ::= call-fill-field | call-read-form
            param-fill-field-field-id ::= "<parameter=field_id>\n" xtext "\n</parameter>\n"
            param-fill-field-value ::= "<parameter=value>\n" xtext "\n</parameter>\n"
            param-fill-field-source ::= "<parameter=source>\n" ( "profile" | "user" ) "\n</parameter>\n"
            param-fill-field-person-id ::= "<parameter=person_id>\n" xtext "\n</parameter>\n"
            call-fill-field ::= "<function=fill_field>\n" param-fill-field-field-id param-fill-field-value param-fill-field-source param-fill-field-person-id? "</function>"
            call-read-form ::= "<function=read_form>\n" "</function>"
            xtext ::= [^<\x00-\x1F]+
            jstring ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}) )* "\""
            jinteger ::= "-"? ([0-9] | [1-9] [0-9]{0,15})
            jnumber ::= jinteger ("." [0-9]{1,16})?
            jboolean ::= "true" | "false"
            jarray ::= "[" ( jstring ( ", " jstring )* )? "]"

            """.trimIndent(),
        )
    }

    @Test
    fun `the grammar names every registered tool and no other`() {
        val grammar = qwen.grammar(tools)

        tools.forEach { assertThat(grammar).contains("<function=${it.name}>") }
        assertThat(grammar).doesNotContain("<function=finish>")
        assertThat(grammar.lines().first { it.startsWith("call ::=") }).isEqualTo("call ::= call-fill-field | call-ask-user | call-read-form")
    }

    // ── The Hermes / Qwen2.5 / Qwen3 JSON format ──

    private val hermes = HermesToolCallFormat()

    @Test
    fun `Hermes writes and reads a JSON call`() {
        val args = obj("field_id" to str("f1"), "value" to str("Mia \"M\" Müller"), "source" to str("user"))

        val text = hermes.renderCall("fill_field", args, tools)

        assertThat(text).isEqualTo(
            "<tool_call>\n{\"name\": \"fill_field\", \"arguments\": {\"field_id\": \"f1\", \"value\": \"Mia \\\"M\\\" Müller\", \"source\": \"user\"}}\n</tool_call>",
        )
        assertThat(hermes.parse(text, tools)).isEqualTo(ParsedCall.Call("fill_field", args))
        assertThat(hermes.renderResult("x", "{}")).isEqualTo("<tool_response>\n{}\n</tool_response>")
    }

    @Test
    fun `Hermes accepts arguments carried as a string and refuses what is not a call`() {
        val carried = "<tool_call>\n{\"name\": \"ask_user\", \"arguments\": \"{\\\"question\\\": \\\"Q?\\\"}\"}\n</tool_call>"

        assertThat((hermes.parse(carried, tools) as ParsedCall.Call).args["question"]).isEqualTo(JsonPrimitive("Q?"))
        assertThat(hermes.parse("<tool_call>\nnot json\n</tool_call>", tools)).isInstanceOf(ParsedCall.Invalid::class.java)
        assertThat(hermes.parse("<tool_call>\n{\"name\": \"nope\", \"arguments\": {}}\n</tool_call>", tools)).isInstanceOf(ParsedCall.Invalid::class.java)
    }

    @Test
    fun `the Hermes grammar puts required arguments first and each optional one after (golden)`() {
        val grammar = hermes.grammar(listOf(fill, read))

        assertThat(grammar).isEqualTo(
            """
            root ::= "<tool_call>\n" call "\n</tool_call>"
            call ::= call-fill-field | call-read-form
            args-fill-field ::= "{" "\"field_id\": " jstring ", " "\"value\": " jstring ", " "\"source\": " ( "\"profile\"" | "\"user\"" ) ( ", " "\"person_id\": " jstring )? "}"
            call-fill-field ::= "{\"name\": \"fill_field\", \"arguments\": " args-fill-field "}"
            args-read-form ::= "{" "}"
            call-read-form ::= "{\"name\": \"read_form\", \"arguments\": " args-read-form "}"
            jstring ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}) )* "\""
            jinteger ::= "-"? ([0-9] | [1-9] [0-9]{0,15})
            jnumber ::= jinteger ("." [0-9]{1,16})?
            jboolean ::= "true" | "false"
            jarray ::= "[" ( jstring ( ", " jstring )* )? "]"

            """.trimIndent(),
        )
    }

    @Test
    fun `the format of a model profile is data`() {
        assertThat(ToolFormatId.QWEN.create()).isInstanceOf(QwenToolCallFormat::class.java)
        assertThat(ToolFormatId.HERMES_JSON.create()).isInstanceOf(HermesToolCallFormat::class.java)
    }
}
