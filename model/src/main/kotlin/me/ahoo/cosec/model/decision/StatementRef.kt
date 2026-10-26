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

package me.ahoo.cosec.model.decision

import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.scope.ScopeResolution

/*
 * The decision types of the binding model (RFC 0004 §5.1, §6.5). They live in their own package because
 * `me.ahoo.cosec.model.StatementRef`, `Decision` and `Cause`, the layered ones of RFC 0002, stay in use until the switch
 * to bindings (RFC 0004 PR-7) and are removed with the layers (PR-8).
 */

/** The rule that made a decision. */
sealed interface StatementRef {
    /**
     * Statement [index] of [policy], named [name], which applied through [via]. The blacklist rule and the root policy
     * are policies too: `cosec:blacklist`, through [BindingRef.Builtin], and `cosec:root`, through its break-glass
     * binding.
     */
    data class Policy(val policy: PolicyId, val index: Int, val name: String, val via: BindingRef) : StatementRef

    /** `ScopeResolver` rejected the declared scope (RFC 0004 §4.6). The engine never sees such a request. */
    data class ScopeRejected(val reason: ScopeResolution.Rejected.Reason) : StatementRef
}

/** How a policy came to apply to a request. */
sealed interface BindingRef {
    /** Through binding [id] of a [document]. */
    data class Bound(val id: BindingId, val document: DocumentKind) : BindingRef

    /** Attached by the principal's memberships (RFC 0004 §4.4), as 7.x `principal.policies`. */
    data class Attached(val policy: PolicyId) : BindingRef

    /** Part of every snapshot: the blacklist rule (RFC 0004 §4.7). */
    data object Builtin : BindingRef
}

/**
 * The snapshot document a binding is in (RFC 0004 §7.1). [APP] documents belong to the platform layer: they apply to
 * every tenant (§4.3).
 */
enum class DocumentKind { PLATFORM, APP, TENANT }
