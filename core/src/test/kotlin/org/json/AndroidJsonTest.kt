package org.json

import kotlin.test.Test
import kotlin.test.assertEquals

/** Eklentilerin dayandığı, Android'e özgü org.json davranışları. */
class AndroidJsonTest {
    @Test
    fun getStringConvertsNonStringValues() {
        val json = JSONObject("""{"headers":[{"Referer":"https://a/"}],"count":3}""")
        assertEquals("""[{"Referer":"https:\/\/a\/"}]""", json.getString("headers"))
        assertEquals("3", json.getString("count"))
    }

    @Test
    fun keysAreTypedAsStrings() {
        val keys: Iterator<String> = JSONObject("""{"a":1,"b":2}""").keys()
        assertEquals(listOf("a", "b"), keys.asSequence().toList())
    }
}
