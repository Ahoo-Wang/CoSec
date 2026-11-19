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

package me.ahoo.cosec.testkit

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.engine.Engine
import me.ahoo.cosec.engine.EngineRequest
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineBuilder
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.Authenticator
import java.util.concurrent.CompletableFuture

/**
 * A host whose rule is "permit the request", over no documents: for the kit's forms whose subject is what a permitted
 * request carries (its context, the identity a gateway forwards for it), not the decision. Authentication is real.
 */
internal object PermittingHost {
    /** A pipeline over [authenticator] that permits every request, with whatever else [configure] adds. */
    fun pipeline(authenticator: Authenticator, configure: PipelineBuilder.() -> Unit = {}): Pipeline =
        Pipeline.builder(authenticator, SuspendingSnapshotSource(NO_DOCUMENTS))
            .engine(PERMITTING)
            .apply(configure)
            .build()

    /** The host's rule, "permit the request". */
    private val PERMITTING = object : Engine {
        override fun evaluate(request: EngineRequest, snapshot: SnapshotView, trace: TraceLevel): Evaluation =
            Evaluation(Decision.Permit(StatementRef.Policy(PERMIT, 0, "", BindingRef.Attached(PERMIT))), null)
    }

    private val PERMIT = PolicyId("testkit-permitted-request")

    private val NO_DOCUMENTS = object : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            CompiledDocument.platform(emptyList(), emptyList()),
            key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }
}
