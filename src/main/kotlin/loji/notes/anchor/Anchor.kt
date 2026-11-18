package loji.notes.anchor

import loji.notes.model.Note

/**
 * 锚点 = 行号 + 上下文指纹。
 *
 * 代码被人挪动后，靠「规范化后的行文本指纹 + 上下各 2 行」在附近搜索，把注释重新钉回它原本指向的那行。
 * 搜索窗口内找不到足够像的行，就判定锚点丢失（卡片加警示），而不是硬指到某行上。
 */
object Anchor {

    const val CONTEXT_LINES = 2
    const val SEARCH_RADIUS = 400

    private val whitespace = Regex("\\s+")

    fun normalize(line: String): String = whitespace.replace(line.trim(), " ").lowercase()

    /** FNV-1a 64，够短、够散，落盘可读。 */
    fun fingerprint(line: String): String {
        var h = -0x340d631b7bdddcdbL // 14695981039346656037
        for (b in normalize(line).toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h)
    }

    fun context(lines: List<String>, line: Int): Pair<List<String>, List<String>> {
        val before = (line - CONTEXT_LINES until line)
            .filter { it in lines.indices }
            .map { lines[it] }
        val after = (line + 1..line + CONTEXT_LINES)
            .filter { it in lines.indices }
            .map { lines[it] }
        return before to after
    }

    private fun similarity(a: List<String>, b: List<String>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        var score = 0
        // 越靠近锚点的行权重越高
        for (i in a.indices.reversed()) {
            if (i >= b.size) continue
            val x = normalize(a[i])
            val y = normalize(b[i])
            if (x.isNotEmpty() && x == y) score += 6 + 3 * (a.size - i)
        }
        return score
    }

    /** 候选行 [at] 与注释的匹配分；0 表示完全不像。 */
    fun score(note: Note, lines: List<String>, at: Int): Int {
        if (at !in lines.indices) return 0
        val line = lines[at]
        var score = 0
        if (fingerprint(line) == note.fingerprint && normalize(line).isNotEmpty()) {
            score += 100
        }
        val (before, after) = context(lines, at)
        score += similarity(note.ctxBefore, before)
        score += similarity(note.ctxAfter, after)
        return score
    }

    /**
     * 返回重新定位后的行号；找不到可信位置返回 null。
     * 原始行仍然匹配时立刻返回，避免无谓搜索。
     */
    fun relocate(note: Note, lines: List<String>, radius: Int = SEARCH_RADIUS): Int? {
        if (lines.isEmpty()) return null
        val original = note.line
        if (original in lines.indices && normalize(lines[original]).isNotEmpty() &&
            fingerprint(lines[original]) == note.fingerprint
        ) {
            return original
        }

        var best = -1
        var bestScore = 0
        var bestDistance = Int.MAX_VALUE
        val from = (original - radius).coerceAtLeast(0)
        val to = (original + radius).coerceAtMost(lines.lastIndex)
        for (i in from..to) {
            val s = score(note, lines, i)
            if (s <= 0) continue
            val d = kotlin.math.abs(i - original)
            if (s > bestScore || (s == bestScore && d < bestDistance)) {
                best = i
                bestScore = s
                bestDistance = d
            }
        }
        // 阈值：至少上下文命中（6 分）或指纹命中（100 分）
        return if (best >= 0 && bestScore >= 6) best else null
    }

    /** 打开文件时校正所有注释的行号，返回是否有改动。 */
    fun resolveAll(notes: MutableList<Note>, lines: List<String>): Boolean {
        var changed = false
        for (idx in notes.indices) {
            val note = notes[idx]
            val hit = relocate(note, lines)
            if (hit == null) {
                if (!note.detached) {
                    notes[idx] = note.copy(detached = true)
                    changed = true
                }
            } else {
                val lineText = lines[hit]
                val fixed = note.copy(
                    line = hit,
                    fingerprint = fingerprint(lineText),
                    ctxBefore = context(lines, hit).first,
                    ctxAfter = context(lines, hit).second,
                    detached = false,
                )
                if (fixed != note) {
                    notes[idx] = fixed
                    changed = true
                }
            }
        }
        return changed
    }
}
