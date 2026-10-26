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

package me.ahoo.cosec.model

/**
 * Everything one evaluation reads. Immutable.
 *
 * Neither this class nor its parts are data classes: nothing needs `copy` or `componentN`, and only tests compare them.
 */
class AuthzRequest(
    val subject: Subject,
    val scope: Scope,
    val action: Action,
    val resource: Resource,
    val environment: Environment,
    /** Host data for the adapted legacy matchers (RFC 0002 stages 2–3). The engine never reads it. */
    val host: HostFacts = HostFacts.None,
)

class Subject(
    val id: String,
    val tenantId: String,
    val authenticated: Boolean,
    /** The roles as given, possibly with an `@space` suffix. The adapter scopes them. */
    val roles: Set<String>,
    val policies: Set<String>,
    val attributes: Map<String, Any>,
    /** Decided beforehand by `RootPrincipalMatcher` (RFC 0002 stages 2–3); a break-glass binding replaces it later. */
    val root: Boolean,
)

/**
 * The scope a request is made in.
 *
 * It can only be [declared]: it comes from client headers or query parameters (`CoSec-App-Id`, `CoSec-Space-Id`) and
 * nobody has checked membership. Checked scopes are `me.ahoo.cosec.model.scope.Scope`, which only `ScopeResolver`
 * constructs (RFC 0004 §4.6); this class is removed with the layers (RFC 0004 PR-8).
 */
class Scope private constructor(val tenantId: String, val appId: String, val spaceId: String, val source: Source) {
    enum class Source { DECLARED, RESOLVED }

    companion object {
        fun declared(tenantId: String, appId: String, spaceId: String): Scope =
            Scope(tenantId, appId, spaceId, Source.DECLARED)
    }
}

/** For HTTP, `namespace` is `http` and `verb` the method. */
class Action(val namespace: String, val verb: String)

/** For HTTP, `type` is `http`, `id` the request path and `attributes` the request's `resource.*` attributes. */
class Resource(val type: String, val id: String, val attributes: Map<String, String>)

class Environment(
    val remoteIp: String,
    val requestId: String,
    val deviceId: String,
    /** Decided beforehand by `BlacklistChecker` (RFC 0002 stages 2–3); an attribute provider supplies it later. */
    val blocked: Boolean,
)

/** Host data the engine does not know about. The adapter in `cosec-core` provides it. */
interface HostFacts {
    object None : HostFacts
}
