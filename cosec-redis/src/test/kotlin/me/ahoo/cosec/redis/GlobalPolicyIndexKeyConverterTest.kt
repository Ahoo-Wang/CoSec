package me.ahoo.cosec.redis

import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*

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