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

package me.ahoo.cosec.webmvc.testing

import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.runtime.http.RequestGate
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.webmvc.PipelineServletFilter
import me.ahoo.cosec.webmvc.SecurityContextHolder
import me.ahoo.cosec.webmvc.ServletRequests.setSecurityContext

/**
 * For tests only: how a test of a Spring MVC application puts CoSec in front of the code under test (`PIP-29`). In
 * production the adapter binds every context, and nothing else does (round 2 L5): an application never imports this.
 *
 * - [filter] is CoSec's own filter over a test pipeline (`PipelineFixture.pipeline`, or [me.ahoo.cosec.runtime.Pipeline.behindGateway]
 *   for a service behind a gateway): the faithful form for a handler test with `MockMvc`, or a `@WebMvcTest` slice
 *   that declares it as a bean (with [me.ahoo.cosec.webmvc.SecurityContextArgumentResolver] for a `SecurityContext`
 *   parameter). A slice needs no CoSec auto-configuration: it needs the filter.
 * - [bind] and [withSecurityContext] put a context the kit made (`PermittedRequest`, `PipelineFixture.securityContext`)
 *   where the code under test reads it: the request, [SecurityContextHolder]. For a unit test of a function.
 *
 * Every binder refuses a context the pipeline did not make, so a test never runs as anonymous without saying so; a bound
 * context whose request has ended is denied as any other (`PIP-26`).
 */
@ExperimentalCoSecApi
object CoSecTestBindings {
    /** CoSec's filter over [gate], built as the starter builds it: the same class, problem writer and request end. */
    fun filter(gate: RequestGate): Filter = PipelineServletFilter(gate)

    /** [request] with [context] bound, as the adapter binds it for the handler. */
    fun bind(request: HttpServletRequest, context: SecurityContext) {
        request.setSecurityContext(pipelineMade(context))
    }

    /** [block]'s answer, run with [context] in [SecurityContextHolder]; what the holder had before is put back. */
    fun <T> withSecurityContext(context: SecurityContext, block: () -> T): T {
        val bound = pipelineMade(context)
        val previous = SecurityContextHolder.context
        SecurityContextHolder.setContext(bound)
        try {
            return block()
        } finally {
            if (previous == null) SecurityContextHolder.remove() else SecurityContextHolder.setContext(previous)
        }
    }

    private fun pipelineMade(context: SecurityContext): SecurityContext {
        require(RequestSecurity.of(context) != null) { REFUSED }
        return context
    }

    internal const val REFUSED = "Only a context CoSec's pipeline made can be bound: use PermittedRequest.withToken, " +
        "forSubject or withIdentity, or PipelineFixture.securityContext (cosec-testkit). A context made by hand reads " +
        "as no context at all (PIP-27)."
}
