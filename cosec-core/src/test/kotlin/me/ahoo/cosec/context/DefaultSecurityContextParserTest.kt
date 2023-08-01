package me.ahoo.cosec.context

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.token.PrincipalConverter
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DefaultSecurityContextParserTest {

    @Test
    fun parse() {
        val request = mockk<Request> {
            every { getHeader(AUTHORIZATION_HEADER_KEY) } returns "Bearer token"
        }
        val principalConverter = mockk<PrincipalConverter> {
            every { asPrincipal(any()) } returns mockk()
        }
        val securityContextParser = DefaultSecurityContextParser(principalConverter)

        assertThat(securityContextParser.parse(request), notNullValue())

        verify {
            request.getHeader(AUTHORIZATION_HEADER_KEY)
            principalConverter.asPrincipal(any())
        }
    }

    @Test
    fun parseIfEmpty() {
        val request = mockk<Request> {
            every { getHeader(AUTHORIZATION_HEADER_KEY) } returns ""
        }

        val securityContextParser = DefaultSecurityContextParser(mockk())
        val securityContext = securityContextParser.ensureParse(request)
        assertThat(securityContext, notNullValue())
        assertThat(securityContext.principal.anonymous(), equalTo(true))
        verify {
            request.getHeader(AUTHORIZATION_HEADER_KEY)
        }
    }

    @Test
    fun parseIfError() {
        val request = mockk<Request> {
            every { getHeader(AUTHORIZATION_HEADER_KEY) } throws RuntimeException("parse error")
        }
        val securityContextParser = DefaultSecurityContextParser(mockk())
        val securityContext = securityContextParser.ensureParse(request)
        assertThat(securityContext, notNullValue())
        assertThat(securityContext.principal.anonymous(), equalTo(true))
        verify {
            request.getHeader(AUTHORIZATION_HEADER_KEY)
        }
    }
}
