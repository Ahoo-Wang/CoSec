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

import jakarta.servlet.http.HttpServletRequest
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.engine.Engine
import me.ahoo.cosec.engine.EngineRequest
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.webmvc.ServletRequests.securityContextOrNull
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.concurrent.CompletableFuture

/** A pipeline that permits every request, for tests that need a context CoSec made (the readers take no other). */
internal object PermittingPipeline {
    private object Views : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            CompiledDocument.platform(emptyList(), emptyList()),
            key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }

    val pipeline: Pipeline = Pipeline.builder(Authenticator { AuthnResult.Anonymous }, SuspendingSnapshotSource(Views))
        .engine(object : Engine {
            override fun evaluate(request: EngineRequest, snapshot: SnapshotView, trace: TraceLevel) = Evaluation(
                Decision.Permit(
                    StatementRef.Policy(PolicyId("p"), 0, "s", BindingRef.Bound(BindingId("g"), DocumentKind.PLATFORM))
                ),
                null,
            )
        })
        .build()

    /** The context of a request the servlet filter permitted (and has ended since). */
    fun context(): SecurityContext {
        var context: SecurityContext? = null
        PipelineServletFilter(pipeline).doFilter(MockHttpServletRequest("GET", "/orders"), MockHttpServletResponse()) {
                request, _ ->
            context = (request as HttpServletRequest).securityContextOrNull()
        }
        return checkNotNull(context)
    }
}
