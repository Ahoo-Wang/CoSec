package me.ahoo.cosec.redis

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class GlobalPolicyIndexKeyConverterTest {
    private val keyConverter = GlobalPolicyIndexKeyConverter("key")

    @Test
    fun asKey() {
        assertThat(keyConverter.asKey(""), equalTo("key"))
    }

    @Test
    fun testToString() {
        assertThat(keyConverter.toString(), equalTo("GlobalPolicyKeyConverter(key='key')"))
    }
}
