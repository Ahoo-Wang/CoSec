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

package me.ahoo.cosec.engine

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.decision.BindingRef

/** Receives the candidates of one request, in [CandidateOrder]. */
internal interface CandidateVisitor {
    /** [policy] applies through [via]. Returns true to stop. */
    fun candidate(policy: CompiledPolicy, via: BindingRef): Boolean

    /**
     * [policy] applies through [via], one of several bindings of its document that reach it ([BoundEntry.repeat] is
     * [slot]). An evaluator need not evaluate it again through the later ones (`ALG-9`); listing them all, this calls
     * [candidate]. Returns true to stop.
     */
    fun repeated(policy: CompiledPolicy, via: BindingRef.Bound, slot: Int): Boolean = candidate(policy, via)

    /** A binding that would apply to the request had it not expired. */
    fun expired(via: BindingRef.Bound)

    /** An attachment or binding names [policyId], which the snapshot does not have (or does not let it attach). */
    fun missing(policyId: String)

    /** An attachment or tenant binding names [policyId], which both the tenant document and the platform define. */
    fun ambiguous(policyId: String)
}
