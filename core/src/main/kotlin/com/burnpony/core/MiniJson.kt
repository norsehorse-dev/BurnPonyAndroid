//
// MiniJson.kt
// BurnPony core (Kotlin)
//
// Minimal strict JSON parser, sufficient for the two tiny objects in the
// BurnPony v1 wire format (payload and envelope). Hand-rolled so the core
// module stays dependency-free; DECODING is deliberately order-agnostic and
// tolerant of whitespace, exactly like JSONDecoder on iOS and JSON.parse in
// the viewer. Encoding is never done here — the deterministic serializers in
// BurnPonyCrypto handle that.
//
// Supported: objects, strings (all escapes incl. \uXXXX surrogate pairs),
// integer and decimal numbers, booleans, null, nested objects/arrays.
// Rejects: trailing garbage, unterminated input, bad escapes, bare words.
//

package com.burnpony.core

internal object MiniJson {

    class JsonException(message: String) : Exception(message)

    /** Parses a complete JSON object document; throws JsonException otherwise. */
    fun parseObject(text: String): Map<String, Any?> {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd) throw JsonException("trailing characters")
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: throw JsonException("not an object")
    }

    private class Parser(private val s: String) {
        var pos = 0
        val atEnd: Boolean get() = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos] in " \t\n\r") pos++
        }

        fun parseValue(): Any? {
            if (atEnd) throw JsonException("unexpected end")
            return when (s[pos]) {
                '{' -> parseObjectValue()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> parseNumber()
            }
        }

        private fun parseObjectValue(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                map[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw JsonException("expected , or }")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { pos++; return list }
            while (true) {
                skipWhitespace()
                list.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw JsonException("expected , or ]")
                }
            }
        }

        fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (atEnd) throw JsonException("unterminated string")
                when (val c = s[pos++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd) throw JsonException("unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\u0008')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> out.append(parseHexChar())
                            else -> throw JsonException("bad escape \\$e")
                        }
                    }
                    else -> {
                        if (c.code < 0x20) throw JsonException("raw control character in string")
                        out.append(c)
                    }
                }
            }
        }

        private fun parseHexChar(): Char {
            if (pos + 4 > s.length) throw JsonException("bad \\u escape")
            val hex = s.substring(pos, pos + 4)
            pos += 4
            val code = hex.toIntOrNull(16) ?: throw JsonException("bad \\u escape")
            return code.toChar()
        }

        private fun parseNumber(): Number {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var isDouble = false
            if (pos < s.length && s[pos] == '.') {
                isDouble = true
                pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val token = s.substring(start, pos)
            if (token.isEmpty() || token == "-") throw JsonException("bad number")
            return if (isDouble) {
                token.toDoubleOrNull() ?: throw JsonException("bad number")
            } else {
                token.toLongOrNull() ?: throw JsonException("bad number")
            }
        }

        private fun <T> parseLiteral(literal: String, value: T): T {
            if (!s.startsWith(literal, pos)) throw JsonException("bad literal")
            pos += literal.length
            return value
        }

        private fun peek(): Char {
            if (atEnd) throw JsonException("unexpected end")
            return s[pos]
        }

        private fun expect(c: Char) {
            if (atEnd || s[pos] != c) throw JsonException("expected $c")
            pos++
        }
    }
}
