package me.ahoo.cosec.context

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.token.PrincipalConverter
import me.ahoo.cosec.token.RevocableTokenVerifier
import me.ahoo.cosec.token.SimpleAccessToken
import me.ahoo.cosec.token.SimpleTokenPrincipal
import me.ahoo.cosec.token.TokenRevokedException
import me.ahoo.cosec.token.TokenStore
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.test.asserts.assert
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
        securityContext.principal.anonymous.assert().isTrue()
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
        securityContext.principal.anonymous.assert().isTrue()
        verify {
            request.getHeader(AUTHORIZATION_HEADER_KEY)
        }
    }

    @Test
    fun parseWhenTokenRevoked() {
        val request = mockk<Request> {
            every { getHeader(AUTHORIZATION_HEADER_KEY) } returns "revoked-token"
        }
        val tokenPrincipal = SimpleTokenPrincipal("tokenId", SimplePrincipal.ANONYMOUS)
        val accessToken = SimpleAccessToken("revoked-token")
        val delegateVerifier = mockk<TokenVerifier>()
        every { delegateVerifier.verify<TokenPrincipal>(accessToken) } returns tokenPrincipal
        every { delegateVerifier.toPrincipal(accessToken) } returns tokenPrincipal
        val tokenStore = mockk<TokenStore>()
        every { tokenStore.isRevoked("tokenId") } returns true
        val securityContextParser = DefaultSecurityContextParser(RevocableTokenVerifier(delegateVerifier, tokenStore))

        assertThrows<TokenRevokedException> {
            securityContextParser.parse(request)
        }
    }
}
