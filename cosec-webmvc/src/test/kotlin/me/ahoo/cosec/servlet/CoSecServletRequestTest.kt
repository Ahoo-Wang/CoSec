/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.cosec.servlet

import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.jwt.InjectSecurityContextParser
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.MediaType
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import reactor.kotlin.core.publisher.toMono
import java.io.BufferedReader
import java.net.URI

class CoSecServletRequestTest {
    @Test
    fun test() {
        val delegate = mockk<HttpServletRequest> {
            every { getHeader("key") } returns "value"
            every { getHeader("not-exists") } returns null
            every { getHeader(AppIdCapable.APP_ID_KEY) } returns null
            every { queryString } returns "key=value&${AppIdCapable.APP_ID_KEY}=appId"
        }
        val request = cosecRequest(delegate)
        request.toString().assert().isEqualTo(
            "Request([appId]:[remoteIp] - requestId [method path] [http://origin] [http://referer])"
        )
        request.getHeader("key").assert().isEqualTo("value")
        request.getQuery("key").assert().isEqualTo("value")
        request.getHeader("not-exists").assert().isEqualTo("")
    }

    @Test
    fun formBodyParameterIsNotAQuery() {
        val delegate = formPost(queryString = null)
        val request = cosecRequest(delegate)
        request.getQuery(AppIdCapable.APP_ID_KEY).assert().isEmpty()
        request.appId.assert().isEmpty()
        delegate.getParameter(AppIdCapable.APP_ID_KEY).assert().isEqualTo("fromBody")
    }

    @Test
    fun queryWinsOverFormBodyParameter() {
        val request = cosecRequest(formPost(queryString = "${AppIdCapable.APP_ID_KEY}=fromQuery"))
        request.appId.assert().isEqualTo("fromQuery")
    }

    @Test
    fun bodyIsReadableAfterAuthorization() {
        val delegate = BodyGuard(formPost(queryString = "${SpaceIdCapable.SPACE_ID_KEY}=space"))
        var seen: Request? = null
        val filter = AuthorizationFilter(
            InjectSecurityContextParser,
            Authorization { request, _ ->
                seen = request
                listOf(request.appId, request.spaceId, request.deviceId, request.getQuery("other"))
                AuthorizeResult.ALLOW.toMono()
            },
            ServletRequestParser(ServletRemoteIpResolver),
        )
        val chain = MockFilterChain()
        filter.doFilter(delegate, MockHttpServletResponse(), chain)

        chain.request.assert().isSameAs(delegate)
        seen!!.spaceId.assert().isEqualTo("space")
        delegate.touched.assert().isEmpty()
        val body = (delegate.request as MockHttpServletRequest).inputStream.bufferedReader().use { it.readText() }
        body.assert().isEqualTo(FORM_BODY)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        nullValues = ["NULL"],
        value = [
            "a=b                  | a | b",
            "a=1&a=2              | a | 1",
            "a=&a=2               | a | ''",
            "a&a=2                | a | ''",
            "a=x%20y+z            | a | x y z",
            "a=%E4%BD%A0          | a | 你",
            "a%5B0%5D=v           | a[0] | v",
            "a=b=c                | a | b=c",
            "x=1&&a=2             | a | 2",
            "a=%2B                | a | +",
            "NULL                 | a | ''",
            "''                   | a | ''",
            "b=1                  | a | ''",
        ]
    )
    fun queryIsDecodedLikeWebFlux(queryString: String?, key: String, expected: String) {
        val delegate = MockHttpServletRequest("GET", "/path").apply { this.queryString = queryString }
        cosecRequest(delegate).getQuery(key).assert().isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "a=%zz           | a | ''",
            "a=%             | a | ''",
            "a=%E4%B         | a | ''",
            "%zz=1&a=2       | a | 2",
            "a=%zz&b=2       | b | 2",
            "a=%zz&a=2       | a | 2",
        ]
    )
    fun malformedPairIsAbsent(queryString: String, key: String, expected: String) {
        val delegate = MockHttpServletRequest("GET", "/path").apply { this.queryString = queryString }
        cosecRequest(delegate).getQuery(key).assert().isEqualTo(expected)
    }

    private fun cosecRequest(delegate: HttpServletRequest) = CoSecServletRequest(
        delegate = delegate,
        path = "path",
        method = "method",
        remoteIp = "remoteIp",
        origin = URI.create("http://origin"),
        referer = URI.create("http://referer"),
        requestId = "requestId"
    ).withAttributes(emptyMap())

    private fun formPost(queryString: String?) = MockHttpServletRequest("POST", "/path").apply {
        this.queryString = queryString
        contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE
        setContent(FORM_BODY.toByteArray())
        // A container parses the body into the parameter map on the first getParameter* call.
        addParameter(AppIdCapable.APP_ID_KEY, "fromBody")
        addParameter("other", "fromBody")
    }

    /** Records every call that would make a servlet container read the request body. */
    private class BodyGuard(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
        val touched = mutableListOf<String>()

        override fun getParameter(name: String): String? = guard("getParameter") { super.getParameter(name) }
        override fun getParameterMap(): Map<String, Array<String>> =
            guard("getParameterMap") { super.getParameterMap() }
        override fun getParameterNames() = guard("getParameterNames") { super.getParameterNames() }
        override fun getParameterValues(name: String): Array<String>? =
            guard("getParameterValues") { super.getParameterValues(name) }
        override fun getReader(): BufferedReader = guard("getReader") { super.getReader() }
        override fun getInputStream(): ServletInputStream = guard("getInputStream") { super.getInputStream() }
        override fun getParts() = guard("getParts") { super.getParts() }

        private fun <T> guard(name: String, block: () -> T): T {
            touched += name
            return block()
        }
    }

    companion object {
        private const val FORM_BODY = "${AppIdCapable.APP_ID_KEY}=fromBody&other=fromBody"
    }
}
