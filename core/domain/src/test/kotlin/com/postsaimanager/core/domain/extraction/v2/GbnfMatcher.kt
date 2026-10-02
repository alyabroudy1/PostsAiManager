package com.postsaimanager.core.domain.extraction.v2

/**
 * A small matcher for the GBNF subset [GrammarSyntax] writes, so tests can check that the grammar
 * accepts the answers a correct model gives and rejects the ones the id sets must exclude, without
 * a model or llama.cpp: rules `name ::= a | b`, string literals, char classes (`[a-z]`, `[^"]`),
 * rule references, groups, and the suffixes `?`, `*`, `+` and `{m,n}`.
 *
 * It computes, for a node and a start position, every position a match can end at (no left
 * recursion, which the grammars here do not have).
 */
internal class GbnfMatcher(grammar: String, private val root: String = "root") {

    private sealed interface Node {
        val id: Int
    }

    private class Lit(override val id: Int, val text: String) : Node
    private class Cls(override val id: Int, val negate: Boolean, val ranges: List<CharRange>) : Node
    private class Ref(override val id: Int, val name: String) : Node
    private class Seq(override val id: Int, val items: List<Node>) : Node
    private class Alt(override val id: Int, val options: List<Node>) : Node
    private class Rep(override val id: Int, val item: Node, val min: Int, val max: Int?) : Node

    private var nextId = 0
    private val rules: Map<String, Node> = grammar.lines().filter { it.isNotBlank() }.associate { line ->
        val (name, body) = line.split(" ::= ", limit = 2)
        name to Parser(body).parse()
    }

    fun accepts(input: String): Boolean = root in rules && input.length in ends(Ref(-1, root), 0, input, HashMap())

    // ── matching ─────────────────────────────────────────────────────────────

    private fun ends(node: Node, pos: Int, s: String, memo: HashMap<Long, Set<Int>>): Set<Int> {
        val key = node.id.toLong() * 1_000_003L + pos
        if (node.id >= 0) memo[key]?.let { return it }
        val result: Set<Int> = when (node) {
            is Lit -> if (s.startsWith(node.text, pos)) setOf(pos + node.text.length) else emptySet()
            is Cls -> if (pos < s.length && (node.ranges.any { s[pos] in it } != node.negate)) setOf(pos + 1) else emptySet()
            is Ref -> ends(rules.getValue(node.name), pos, s, memo)
            is Alt -> node.options.flatMapTo(HashSet()) { ends(it, pos, s, memo) }
            is Seq -> node.items.fold(setOf(pos)) { at, item -> at.flatMapTo(HashSet()) { ends(item, it, s, memo) } }
            is Rep -> repeat(node, pos, s, memo)
        }
        if (node.id >= 0) memo[key] = result
        return result
    }

    private fun repeat(node: Rep, pos: Int, s: String, memo: HashMap<Long, Set<Int>>): Set<Int> {
        val out = HashSet<Int>()
        var frontier = setOf(pos)
        var count = 0
        val seen = HashSet<Int>()
        if (node.min == 0) out += pos
        while (frontier.isNotEmpty() && (node.max == null || count < node.max)) {
            val next = frontier.flatMapTo(HashSet()) { ends(node.item, it, s, memo) }
            count++
            if (count >= node.min) out += next
            // Past the minimum an unbounded loop only needs each position once.
            frontier = if (node.max == null && count >= node.min) next.filterTo(HashSet()) { seen.add(it) } else next
        }
        return out
    }

    // ── parsing ──────────────────────────────────────────────────────────────

    private inner class Parser(private val src: String) {
        private var i = 0

        fun parse(): Node = alt().also { check(i == src.length) { "unparsed rest at $i of: $src" } }

        private fun ws() {
            while (i < src.length && src[i] == ' ') i++
        }

        private fun alt(): Node {
            val options = mutableListOf(seq())
            while (true) {
                ws()
                if (i < src.length && src[i] == '|') {
                    i++
                    options += seq()
                } else {
                    break
                }
            }
            return if (options.size == 1) options[0] else Alt(nextId++, options)
        }

        private fun seq(): Node {
            val items = mutableListOf<Node>()
            while (true) {
                ws()
                if (i >= src.length || src[i] == '|' || src[i] == ')') break
                items += suffixed(atom())
            }
            return if (items.size == 1) items[0] else Seq(nextId++, items)
        }

        private fun suffixed(node: Node): Node {
            if (i >= src.length) return node
            return when (src[i]) {
                '?' -> { i++; Rep(nextId++, node, 0, 1) }
                '*' -> { i++; Rep(nextId++, node, 0, null) }
                '+' -> { i++; Rep(nextId++, node, 1, null) }
                '{' -> {
                    val close = src.indexOf('}', i)
                    val (lo, hi) = src.substring(i + 1, close).split(',').let { it[0] to it.getOrNull(1) }
                    i = close + 1
                    Rep(nextId++, node, lo.toInt(), if (hi == null) lo.toInt() else hi.takeIf { it.isNotEmpty() }?.toInt())
                }
                else -> node
            }
        }

        private fun atom(): Node {
            val c = src[i]
            return when {
                c == '"' -> literal()
                c == '[' -> charClass()
                c == '(' -> { i++; val inner = alt(); ws(); check(src[i] == ')'); i++; inner }
                else -> {
                    val start = i
                    while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '-' || src[i] == '_')) i++
                    check(i > start) { "unexpected '${src[i]}' at $i of: $src" }
                    Ref(nextId++, src.substring(start, i))
                }
            }
        }

        private fun escaped(): Char {
            i++ // the backslash
            val e = src[i++]
            return when (e) {
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                else -> e
            }
        }

        private fun literal(): Node {
            i++ // opening quote
            val sb = StringBuilder()
            while (src[i] != '"') sb.append(if (src[i] == '\\') escaped() else src[i++])
            i++
            return Lit(nextId++, sb.toString())
        }

        private fun charClass(): Node {
            i++ // [
            val negate = src[i] == '^'
            if (negate) i++
            val ranges = mutableListOf<CharRange>()
            fun one(): Char = if (src[i] == '\\') escaped() else src[i++]
            while (src[i] != ']') {
                val lo = one()
                if (src[i] == '-' && src[i + 1] != ']') {
                    i++
                    ranges += lo..one()
                } else {
                    ranges += lo..lo
                }
            }
            i++
            return Cls(nextId++, negate, ranges)
        }
    }
}
