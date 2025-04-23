package me.ahoo.cosec.context

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.token.PrincipalConverter
import me.ahoo.test.asserts.assert
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class DefaultSecurityContextParserTest {

    @Test
    fun parse() {
        val request = mockk<Request> {
            every { getHeader(AUTHORIZATION_HEADER_KEY) } returns "Bearer token"
        }
        val principalConverter = mockk<PrincipalConverter> {
            every { toPrincipal(any()) } returns mockk()
        }
        val securityContextParser = DefaultSecurityContextParser(principalConverter)
        securityContextParser.parse(request).assert().isNotNull()

        verify {
            request.getHeader(AUTHORIZATION_HEADER_KEY)
            principalConverter.toPrincipal(any())
        }
    }

    @Test
    fun parseIfEmpty() {
        val request = mockk<Request> {
            every { getHeader(AUTHORIZATION_HEADER_KEY) } returns ""
            every { getQuery(AUTHORIZATION_HEADER_KEY) } returns ""
        }

        val securityContextParser = DefaultSecurityContextParser(mockk())
        val securityContext = securityContextParser.ensureParse(request)
        securityContext.assert().isNotNull()
        securityContext.principal.anonymous().assert().isTrue()
        verify {
            request.getHeader(AUTHORIZATION_HEADER_KEY)
            request.getQuery(AUTHORIZATION_HEADER_KEY)
        }
    }

    @Test
    fun parseHeaderEmpty() {
        val request = mockk<Request> {
            every { getHeader(AUTHORIZATION_HEADER_KEY) } returns ""
            every { getQuery(AUTHORIZATION_HEADER_KEY) } returns "Bearer token"
        }
        val principalConverter = mockk<PrincipalConverter> {
            every { toPrincipal(any()) } returns mockk()
        }
        val securityContextParser = DefaultSecurityContextParser(principalConverter)
        val securityContext = securityContextParser.ensureParse(request)
        securityContext.assert().isNotNull()
        verify {
            request.getHeader(AUTHORIZATION_HEADER_KEY)
            request.getQuery(AUTHORIZATION_HEADER_KEY)
            principalConverter.toPrincipal(any())
        }
    }

    @Test
    fun parseIfError() {
        val request = mockk<Request> {
            every { getHeader(AUTHORIZATION_HEADER_KEY) } throws RuntimeException("parse error")
        }
        val securityContextParser = DefaultSecurityContextParser(mockk())
        val securityContext = securityContextParser.ensureParse(request)
        securityContext.assert().isNotNull()
        securityContext.principal.anonymous().assert().isTrue()
        verify {
            request.getHeader(AUTHORIZATION_HEADER_KEY)
        }
    }
}
