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

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.context.SecurityContextHolder
import me.ahoo.cosec.context.request.RemoteIpResolver
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.ProblemEncoder
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.servlet.ServletRequests.setSecurityContext
import java.util.Collections

/** Reads a servlet request into the facts the pipeline decides on (RFC 0006 §5.3.1), once. */
object ServletHttpFactsReader {
    fun read(
        request: HttpServletRequest,
        remoteIpResolver: RemoteIpResolver<HttpServletRequest> = ServletRemoteIpResolver,
        host: HostScope = NO_HOST,
    ): HttpFacts {
        val headers = HashMap<String, List<String>>()
        for (name in Collections.list(request.headerNames)) {
            headers[name] = Collections.list(request.getHeaders(name))
        }
        val cookies = request.cookies.orEmpty().groupBy({ it.name }, { it.value })
        return HttpFacts(
            method = request.method,
            // Encoded, and within the application: the context path is not the app's to authorize.
            rawPath = request.requestURI.removePrefix(request.contextPath),
            rawQuery = request.queryString,
            headers = Headers.of(headers),
            cookies = Cookies.of(cookies),
            remoteAddress = remoteIpResolver.resolve(request),
            host = host,
        )
    }

    val NO_HOST: HostScope = HostScope(null)
}

/**
 * The servlet filter over the coroutine pipeline (RFC 0006 §7.3). Servlet containers run filters on their own request
 * threads, never on an event loop, so the bridge is `runBlocking` there; the pipeline resumes on that thread after a
 * real suspension. It translates and never decides: the [Outcome]'s status, headers and problem body are written as
 * they are, and the chain is called only when the outcome proceeds.
 *
 * The permitted request's `SecurityContext` is bound to [SecurityContextHolder] and the request for the chain, and
 * removed once the request completes, whatever happens.
 */
class PipelineServletFilter(
    private val pipeline: Pipeline,
    private val contributors: List<BridgeContextContributor> = emptyList(),
    private val remoteIpResolver: RemoteIpResolver<HttpServletRequest> = ServletRemoteIpResolver,
    /** The app this host serves (RFC 0004 §4.6); [ServletHttpFactsReader.NO_HOST] when the request declares it. */
    private val host: HostScope = ServletHttpFactsReader.NO_HOST,
) : Filter {
    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val servletRequest = request as HttpServletRequest
        val servletResponse = response as HttpServletResponse
        val facts = ServletHttpFactsReader.read(servletRequest, remoteIpResolver, host)
        val outcome = runBlocking(BridgeContextContributor.capture(contributors)) { pipeline.evaluate(facts) }
        try {
            if (write(servletRequest, servletResponse, outcome)) {
                chain.doFilter(request, response)
            }
        } finally {
            SecurityContextHolder.remove()
        }
    }

    /** Writes [outcome]; true when the request proceeds to the chain. */
    private fun write(request: HttpServletRequest, response: HttpServletResponse, outcome: Outcome): Boolean {
        outcome.headers.forEach { (name, value) -> response.setHeader(name, value) }
        val problem = outcome.problem
        if (problem != null) {
            response.status = outcome.status
            val body = ProblemEncoder.encode(problem)
            response.outputStream.write(body)
            response.outputStream.flush()
            return false
        }
        val securityContext = checkNotNull(outcome.security) { "A proceeding outcome carries its security." }
            .securityContext
        SecurityContextHolder.setContext(securityContext)
        request.setSecurityContext(securityContext)
        return true
    }
}

/** [AuthorizationChecker.check] for servlet code (RFC 0006 §7.6): blocks the calling request thread. */
fun AuthorizationChecker.checkBlocking(security: SecurityContext, action: String, resource: String): Boolean =
    runBlocking { check(security, action, resource) }
