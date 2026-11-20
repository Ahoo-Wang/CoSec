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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.DocumentKind

/**
 * The document that defines a policy a decision named through a binding (`CTL-51`). A statement is shown to a caller who
 * may read the document its *policy* is defined in, never the one its binding lives in: a tenant's binding may point to a
 * platform policy or to an app's permission policy (RFC 0007 §3.1 N1), and its statements are the platform's or the
 * app's.
 *
 * The lookup is the engine's order (`CandidateOrder`): the binding's own document first (a role's policies are there as
 * the role expands), then the platform's, then the app's the view read. A tenant policy of a platform policy's id is the
 * platform's only as an exact copy, which the tenant holds and reads in full anyway. A policy no document defines is a
 * built-in's (the root policy through a break-glass binding): the binding's document, as before. Reads the documents the
 * decision was made on, so a candidate's own policies are the candidate's.
 */
@InternalCoSecApi
internal class PolicyHomes(private val documents: HeldDocuments) {
    private val defined: Map<DocumentKind, Set<PolicyId>> by lazy(LazyThreadSafetyMode.NONE) {
        listOfNotNull(
            documents.platform?.let { DocumentKind.PLATFORM to ids(it) },
            documents.app?.let { DocumentKind.APP to ids(it) },
            documents.tenant?.let { DocumentKind.TENANT to ids(it) },
        ).toMap()
    }

    /** The document of the policy [policy] a binding of [binding]'s document names. */
    fun of(binding: DocumentKind, policy: PolicyId): DocumentKind = when {
        defined[binding]?.contains(policy) == true -> binding
        defined[DocumentKind.PLATFORM]?.contains(policy) == true -> DocumentKind.PLATFORM
        defined[DocumentKind.APP]?.contains(policy) == true -> DocumentKind.APP
        else -> binding
    }

    private fun ids(held: Held): Set<PolicyId> {
        val effective: PolicyDocument = held.entry?.effective ?: RoleExpansion.effective(held.document)
        return effective.policies.mapTo(HashSet()) { it.id }
    }
}
