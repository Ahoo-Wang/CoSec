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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.webmvc

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.ProblemEncoder
import me.ahoo.cosec.runtime.RequestGate
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.http.ApplicationPaths
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.RemoteIpResolver
import me.ahoo.cosec.webmvc.ServletRequests.setSecurityContext
import reactor.core.scheduler.Schedulers
import java.util.Collections
import java.util.Enumeration

/** Reads a servlet request into the facts the pipeline decides on (RFC 0006 §5.3.1), once. */
internal object ServletHttpFactsReader {
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
            // Encoded, and within the application: the context path is not the app's to authorize (PIP-20).
            rawPath = ApplicationPaths.within(request.requestURI, request.contextPath),
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
    private val pipeline: RequestGate,
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
                chain.doFilter(ForwardedHeadersRequest.of(servletRequest, outcome.forward), response)
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
            // As the reactive stacks frame it: with its length, not chunked (round 2 L2).
            response.setContentLength(body.size)
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

/**
 * The request the chain sees, with [Outcome.forward] applied: the headers the gate sets on it, or removes, such as a
 * `CoSec-Identity` a client sent to an enforcing host (RC1-18). Header names match ignoring case, as servlet
 * containers match them.
 */
internal class ForwardedHeadersRequest private constructor(
    request: HttpServletRequest,
    private val forward: Map<String, String?>,
) : HttpServletRequestWrapper(request) {
    /** Whether [name] is one of [forward]'s, and its value there: null when the gate removes it. */
    private fun forwarded(name: String): Pair<Boolean, String?> {
        for ((forwardedName, value) in forward) {
            if (forwardedName.equals(name, ignoreCase = true)) {
                return true to value
            }
        }
        return false to null
    }

    override fun getHeader(name: String): String? {
        val (named, value) = forwarded(name)
        return if (named) value else super.getHeader(name)
    }

    override fun getHeaders(name: String): Enumeration<String> {
        val (named, value) = forwarded(name)
        return if (named) Collections.enumeration(listOfNotNull(value)) else super.getHeaders(name)
    }

    override fun getHeaderNames(): Enumeration<String> {
        val names = Collections.list(super.getHeaderNames()).filterNot { forwarded(it).first }
        return Collections.enumeration(names + forward.filterValues { it != null }.keys)
    }

    override fun getIntHeader(name: String): Int {
        val (named, value) = forwarded(name)
        return if (named) value?.toInt() ?: -1 else super.getIntHeader(name)
    }

    override fun getDateHeader(name: String): Long {
        val (named, value) = forwarded(name)
        return when {
            !named -> super.getDateHeader(name)
            value == null -> -1
            else -> throw IllegalArgumentException("Header [$name] is not a date.")
        }
    }

    companion object {
        /** [request] with [forward] applied; [request] itself when that changes nothing, so it is not wrapped. */
        fun of(request: HttpServletRequest, forward: Map<String, String?>): HttpServletRequest =
            if (forward.none { (name, value) -> value != null || request.getHeader(name) != null }) {
                request
            } else {
                ForwardedHeadersRequest(request, forward)
            }
    }
}

/**
 * [AuthorizationChecker.check] for servlet code (RFC 0006 §7.6): blocks the calling request thread. Refuses to run on
 * a non-blocking thread, such as a Netty event loop: use `checkMono` from `cosec-webflux` there.
 */
fun AuthorizationChecker.checkBlocking(security: SecurityContext, action: String, resource: String): Boolean {
    check(!Schedulers.isInNonBlockingThread()) {
        "checkBlocking would block the non-blocking thread [${Thread.currentThread().name}]; use checkMono."
    }
    return runBlocking { check(security, action, resource) }
}
