package com.postsaimanager.core.domain.agent

/** The tools one agent may call, by name. The order is the order the model is told about them. */
class ToolRegistry(tools: List<AgentTool>) {

    private val byName: Map<String, AgentTool> = tools.associateBy { it.name }

    init {
        require(byName.size == tools.size) { "duplicate tool name in ${tools.map { it.name }}" }
        require(tools.isNotEmpty()) { "an agent needs at least one tool" }
    }

    private val ordered: List<AgentTool> = tools

    operator fun get(name: String): AgentTool? = byName[name]

    val names: List<String> get() = ordered.map { it.name }

    fun specs(): List<ToolSpec> = ordered.map { it.spec() }
}
