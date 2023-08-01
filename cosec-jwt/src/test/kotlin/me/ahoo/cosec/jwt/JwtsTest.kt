package me.ahoo.cosec.jwt

import me.ahoo.cosec.jwt.Jwts.TOKEN_PREFIX
import me.ahoo.cosec.jwt.Jwts.removeBearerPrefix
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class JwtsTest {

    @Test
    fun removeBearerPrefixIfEmpty() {
        assertThat("".removeBearerPrefix(), equalTo(""))
    }

    @Test
    fun removeBearerPrefix() {
        assertThat("${TOKEN_PREFIX}token".removeBearerPrefix(), equalTo("token"))
    }
}
