package io.room.poe2tree.tree

import java.util.Locale

/** Text-only helpers: summed stat lines and node search. No modifier calculations. */
object TreeText {

    private val numberRegex = Regex("""\d+(?:\.\d+)?""")

    data class SummaryLine(val text: String, val count: Int)

    data class Summary(
        val keystones: List<String>,
        val ascendancy: List<SummaryLine>,
        val stats: List<SummaryLine>,
        val attributes: Triple<Int, Int, Int>,
    )

    /**
     * Sums stat lines that share the same wording, e.g. "8% increased Attack Damage" x3 -> "24% increased Attack Damage".
     * Lines are grouped by their text with numbers replaced by placeholders.
     */
    fun summarize(spec: PassiveSpec): Summary {
        val tree = spec.tree
        val keystones = ArrayList<String>()
        val main = LinkedHashMap<String, Acc>()
        val asc = LinkedHashMap<String, Acc>()
        var str = 0; var dex = 0; var int = 0
        for (i in spec.allocatedIndices()) {
            val node = tree.nodes[i]
            if (node.type.isStart) continue
            val view = spec.views[i]
            if (node.type == NodeType.Keystone) {
                keystones += view.name
                continue
            }
            val target = if (node.ascendancyName != null) asc else main
            for (line in view.stats) {
                add(target, line)
                attributeGain(line)?.let { (s, d, n) -> str += s; dex += d; int += n }
            }
        }
        return Summary(
            keystones = keystones.sorted(),
            ascendancy = asc.values.map { it.toLine() },
            stats = main.values.map { it.toLine() }.sortedBy { it.text.lowercase(Locale.ROOT).replace(numberRegex, "") },
            attributes = Triple(str, dex, int),
        )
    }

    private class Acc(val template: String, val values: DoubleArray, var count: Int) {
        fun toLine(): SummaryLine {
            var idx = 0
            val text = if (values.isEmpty()) template else template.replaceEach("\u0000") {
                format(values[idx++])
            }
            return SummaryLine(text, count)
        }
    }

    private fun String.replaceEach(placeholder: String, next: () -> String): String {
        val sb = StringBuilder()
        var i = 0
        while (true) {
            val j = indexOf(placeholder, i)
            if (j < 0) {
                sb.append(substring(i)); break
            }
            sb.append(substring(i, j)).append(next())
            i = j + placeholder.length
        }
        return sb.toString()
    }

    private fun add(map: LinkedHashMap<String, Acc>, line: String) {
        val nums = numberRegex.findAll(line).map { it.value.toDouble() }.toList()
        val template = line.replace(numberRegex, "\u0000")
        val acc = map.getOrPut(template) { Acc(template, DoubleArray(nums.size), 0) }
        if (acc.values.size == nums.size) {
            for (k in nums.indices) acc.values[k] += nums[k]
        }
        acc.count++
    }

    private fun format(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

    private val attrRegex = Regex("""^\+(\d+) to (Strength|Dexterity|Intelligence|all Attributes|Strength and Dexterity|Strength and Intelligence|Dexterity and Intelligence)$""")

    private fun attributeGain(line: String): Triple<Int, Int, Int>? {
        val m = attrRegex.find(line.trim()) ?: return null
        val v = m.groupValues[1].toInt()
        return when (m.groupValues[2]) {
            "Strength" -> Triple(v, 0, 0)
            "Dexterity" -> Triple(0, v, 0)
            "Intelligence" -> Triple(0, 0, v)
            "all Attributes" -> Triple(v, v, v)
            "Strength and Dexterity" -> Triple(v, v, 0)
            "Strength and Intelligence" -> Triple(v, 0, v)
            "Dexterity and Intelligence" -> Triple(0, v, v)
            else -> null
        }
    }

    // ---- Search (PoB DoesNodeMatchSearchParams, text only) ----

    fun searchWords(query: String): List<String> {
        var rest = query.lowercase(Locale.ROOT)
        val words = ArrayList<String>()
        Regex("\"([^\"]*)\"").findAll(rest).forEach { if (it.groupValues[1].isNotBlank()) words += it.groupValues[1] }
        rest = rest.replace(Regex("\"[^\"]*\""), " ")
        words += rest.split(Regex("\\s+")).filter { it.isNotBlank() }
        return words
    }

    fun matches(spec: PassiveSpec, idx: Int, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        val node = spec.tree.nodes[idx]
        if (node.type == NodeType.ClassStart || node.type == NodeType.OnlyImage) return false
        val view = spec.views[idx]
        val need = words.toMutableList()
        fun check(hay: String) {
            val h = hay.lowercase(Locale.ROOT)
            need.removeAll { h.contains(it) }
        }
        check(view.name)
        for (s in view.stats) check(s)
        check(node.type.label)
        node.ascendancyName?.let { check(it) }
        node.unlockAscendancy?.let { check(it) }
        return need.isEmpty()
    }
}
