//
// MiniJsonTest.kt
// The minimal JSON parser used for order-agnostic payload/envelope decoding.
//

package com.burnpony.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MiniJsonTest {

    @Test
    fun parsesPayloadInAnyKeyOrder() {
        val a = MiniJson.parseObject("{\"v\":1,\"t\":\"hi\",\"ah\":0}")
        val b = MiniJson.parseObject("{\"ah\":0,\"v\":1,\"t\":\"hi\"}")
        val c = MiniJson.parseObject(" { \"t\" : \"hi\" , \"ah\" : 0 , \"v\" : 1 } ")
        for (obj in listOf(a, b, c)) {
            assertEquals(1L, obj["v"])
            assertEquals("hi", obj["t"])
            assertEquals(0L, obj["ah"])
        }
    }

    @Test
    fun parsesEscapes() {
        val obj = MiniJson.parseObject(
            "{\"t\":\"line\\nbreak \\\"q\\\" back\\\\slash \\u0041\\u00e9 \\ud83d\\udd25 tab\\t\"}"
        )
        assertEquals("line\nbreak \"q\" back\\slash Aé 🔥 tab\t", obj["t"])
    }

    @Test
    fun parsesControlEscapes() {
        val obj = MiniJson.parseObject("{\"t\":\"\\b\\f\\r\\u0001\"}")
        assertEquals("\u0008\u000C\r\u0001", obj["t"])
    }

    @Test
    fun parsesNumbersBooleansNullNesting() {
        val obj = MiniJson.parseObject(
            "{\"i\":-42,\"d\":3.5,\"e\":1e2,\"b\":true,\"f\":false,\"n\":null," +
                "\"arr\":[1,\"two\",{\"three\":3}],\"obj\":{\"k\":\"v\"}}"
        )
        assertEquals(-42L, obj["i"])
        assertEquals(3.5, obj["d"] as Double, 0.0)
        assertEquals(100.0, obj["e"] as Double, 0.0)
        assertEquals(true, obj["b"])
        assertEquals(false, obj["f"])
        assertEquals(null, obj["n"])
        @Suppress("UNCHECKED_CAST")
        val arr = obj["arr"] as List<Any?>
        assertEquals(3, arr.size)
        assertEquals("two", arr[1])
    }

    @Test
    fun parsesNonAsciiLiterally() {
        val obj = MiniJson.parseObject("{\"t\":\"Пароль 🔥🐴 Frühstück\"}")
        assertEquals("Пароль 🔥🐴 Frühstück", obj["t"])
    }

    @Test
    fun rejectsMalformedDocuments() {
        val bad = listOf(
            "",
            "{",
            "{}extra",
            "[1,2]",              // not an object at top level
            "{\"a\":}",
            "{\"a\":1,}",
            "{'a':1}",
            "{\"a\":undefined}",
            "{\"a\":\"unterminated}",
            "{\"a\":\"bad\\qescape\"}",
            "{\"a\":\"bad\\u00zz\"}",
            "{\"a\":\u0001\"raw control\"}",
        )
        for (doc in bad) {
            assertThrows("should reject: $doc", Exception::class.java) {
                MiniJson.parseObject(doc)
            }
        }
    }

    @Test
    fun rejectsRawControlCharacterInString() {
        assertThrows(MiniJson.JsonException::class.java) {
            MiniJson.parseObject("{\"t\":\"has \u0007 bell\"}")
        }
    }
}
