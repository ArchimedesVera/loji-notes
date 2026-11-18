package loji.notes.store

/**
 * 够用就好的 JSON：对象 / 数组 / 字符串 / 数字 / 布尔 / null。
 * 不引第三方库，保证插件零外部依赖。
 */
sealed interface JsonValue

data object JsonNull : JsonValue

data class JsonString(val value: String) : JsonValue

data class JsonNumber(val raw: String) : JsonValue {
    fun toInt(default: Int = 0): Int = raw.toDoubleOrNull()?.toInt() ?: default
    fun toLong(default: Long = 0L): Long = raw.toDoubleOrNull()?.toLong() ?: default
}

data class JsonBool(val value: Boolean) : JsonValue

class JsonArray(val items: MutableList<JsonValue> = mutableListOf()) : JsonValue {
    fun add(value: JsonValue) = apply { items.add(value) }
}

class JsonObject(val fields: LinkedHashMap<String, JsonValue> = LinkedHashMap()) : JsonValue {
    operator fun get(key: String): JsonValue? = fields[key]

    fun put(key: String, value: JsonValue) = apply { fields[key] = value }
    fun put(key: String, value: String) = put(key, JsonString(value))
    fun put(key: String, value: Int) = put(key, JsonNumber(value.toString()))
    fun put(key: String, value: Long) = put(key, JsonNumber(value.toString()))
    fun put(key: String, value: Boolean) = put(key, JsonBool(value))

    fun string(key: String, default: String = ""): String =
        (fields[key] as? JsonString)?.value ?: default

    fun int(key: String, default: Int = 0): Int =
        (fields[key] as? JsonNumber)?.toInt(default) ?: default

    fun long(key: String, default: Long = 0L): Long =
        (fields[key] as? JsonNumber)?.toLong(default) ?: default

    fun bool(key: String, default: Boolean = false): Boolean =
        (fields[key] as? JsonBool)?.value ?: default

    fun array(key: String): JsonArray? = fields[key] as? JsonArray
    fun obj(key: String): JsonObject? = fields[key] as? JsonObject
}

object Json {

    fun parse(text: String): JsonValue = Parser(text).parseDocument()

    fun write(value: JsonValue, indent: Int = 2): String {
        val sb = StringBuilder()
        writeValue(value, sb, 0, indent)
        sb.append('\n')
        return sb.toString()
    }

    private fun writeValue(value: JsonValue, sb: StringBuilder, depth: Int, step: Int) {
        when (value) {
            JsonNull -> sb.append("null")
            is JsonBool -> sb.append(if (value.value) "true" else "false")
            is JsonNumber -> sb.append(value.raw)
            is JsonString -> writeString(value.value, sb)
            is JsonArray -> {
                if (value.items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append("[\n")
                value.items.forEachIndexed { i, item ->
                    pad(sb, depth + 1, step)
                    writeValue(item, sb, depth + 1, step)
                    if (i != value.items.lastIndex) sb.append(',')
                    sb.append('\n')
                }
                pad(sb, depth, step)
                sb.append(']')
            }
            is JsonObject -> {
                if (value.fields.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                val entries = value.fields.entries.toList()
                entries.forEachIndexed { i, e ->
                    pad(sb, depth + 1, step)
                    writeString(e.key, sb)
                    sb.append(": ")
                    writeValue(e.value, sb, depth + 1, step)
                    if (i != entries.lastIndex) sb.append(',')
                    sb.append('\n')
                }
                pad(sb, depth, step)
                sb.append('}')
            }
        }
    }

    private fun pad(sb: StringBuilder, depth: Int, step: Int) {
        repeat(depth * step) { sb.append(' ') }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    private class Parser(private val src: String) {
        private var i = 0

        fun parseDocument(): JsonValue {
            skipWs()
            val v = parseValue()
            skipWs()
            if (i < src.length) fail("trailing characters")
            return v
        }

        private fun parseValue(): JsonValue {
            skipWs()
            if (i >= src.length) fail("unexpected end of input")
            return when (val c = src[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonString(parseString())
                't' -> { expect("true"); JsonBool(true) }
                'f' -> { expect("false"); JsonBool(false) }
                'n' -> { expect("null"); JsonNull }
                else -> if (c == '-' || c.isDigit()) parseNumber() else fail("unexpected character '$c'")
            }
        }

        private fun parseObject(): JsonObject {
            val obj = JsonObject()
            i++ // {
            skipWs()
            if (i < src.length && src[i] == '}') { i++; return obj }
            while (true) {
                skipWs()
                if (i >= src.length || src[i] != '"') fail("expected object key")
                val key = parseString()
                skipWs()
                if (i >= src.length || src[i] != ':') fail("expected ':'")
                i++
                obj.fields[key] = parseValue()
                skipWs()
                if (i >= src.length) fail("unterminated object")
                when (src[i]) {
                    ',' -> i++
                    '}' -> { i++; return obj }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): JsonArray {
            val arr = JsonArray()
            i++ // [
            skipWs()
            if (i < src.length && src[i] == ']') { i++; return arr }
            while (true) {
                arr.items.add(parseValue())
                skipWs()
                if (i >= src.length) fail("unterminated array")
                when (src[i]) {
                    ',' -> i++
                    ']' -> { i++; return arr }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= src.length) fail("unterminated string")
                val c = src[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= src.length) fail("unterminated escape")
                        when (val e = src[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > src.length) fail("bad unicode escape")
                                val hex = src.substring(i, i + 4)
                                i += 4
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: fail("bad unicode escape"))
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): JsonNumber {
            val start = i
            if (i < src.length && src[i] == '-') i++
            while (i < src.length && (src[i].isDigit() || src[i] == '.' || src[i] == 'e' || src[i] == 'E' || src[i] == '+' || src[i] == '-')) i++
            if (start == i) fail("expected number")
            return JsonNumber(src.substring(start, i))
        }

        private fun expect(literal: String) {
            if (!src.startsWith(literal, i)) fail("expected '$literal'")
            i += literal.length
        }

        private fun skipWs() {
            while (i < src.length && src[i].isWhitespace()) i++
        }

        private fun fail(msg: String): Nothing =
            throw IllegalArgumentException("JSON 解析失败 @$i: $msg")
    }
}
