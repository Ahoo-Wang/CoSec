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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.webmvc

import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.event.BridgeContextContributor
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.ProblemEncoder
import me.ahoo.cosec.runtime.http.RemoteIpResolver
import me.ahoo.cosec.runtime.http.RequestGate
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.webmvc.ServletRequests.setSecurityContext

/**
 * The servlet filter over the coroutine pipeline (RFC 0006 §7.3). Servlet containers run filters on their own request
 * threads, never on an event loop, so the bridge is `runBlocking` there; the pipeline resumes on that thread after a
 * real suspension. It translates and never decides: the [Outcome]'s status, headers and problem body are written as
 * they are, and the chain is called only when the outcome proceeds.
 *
 * The chain gets the request as [PermittedServletRequest] makes it: the gate's forwarded headers, and CoSec's principal
 * as `getUserPrincipal()` when CoSec authenticated the request (`PIP-28`).
 *
 * The permitted request's `SecurityContext` is bound to [CoSecSecurityContextHolder] and the request for the chain, and
 * removed once the request completes, whatever happens. Its security is ended then, or when an async request completes
 * (`PIP-26`): a context kept after that permits nothing.
 */
class PipelineServletFilter @InternalCoSecApi constructor(
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
                val principal = outcome.security?.securityContext?.principal?.takeIf { it.authenticated }
                chain.doFilter(PermittedServletRequest.of(servletRequest, outcome.forward, principal), response)
            }
        } finally {
            CoSecSecurityContextHolder.remove()
            outcome.security?.let { endWhenComplete(servletRequest, it) }
        }
    }

    /**
     * Ends [security] when the request completes (`PIP-26`): now, unless the handler started async processing
     * (`DeferredResult`, `StreamingResponseBody`, a `suspend` handler), whose end the container reports later.
     */
    private fun endWhenComplete(request: HttpServletRequest, security: RequestSecurity) {
        if (request.isAsyncStarted) {
            request.asyncContext.addListener(EndOnCompletion(security))
        } else {
            security.end()
        }
    }

    /** Ends the request's security when its async processing completes, fails or times out. */
    private class EndOnCompletion(private val security: RequestSecurity) : AsyncListener {
        override fun onComplete(event: AsyncEvent) = security.end()

        override fun onError(event: AsyncEvent) = security.end()

        override fun onTimeout(event: AsyncEvent) = security.end()

        // A new async cycle drops the listeners: this one goes on listening.
        override fun onStartAsync(event: AsyncEvent) = event.asyncContext.addListener(this)
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
        CoSecSecurityContextHolder.setContext(securityContext)
        request.setSecurityContext(securityContext)
        return true
    }
}
