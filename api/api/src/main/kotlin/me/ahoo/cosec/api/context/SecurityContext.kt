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

package me.ahoo.cosec.api.context

import me.ahoo.cosec.api.principal.CoSecPrincipal

/** The validated scope of a request (RFC 0004 §4.6): its app, its space, and the principal's roles there. */
interface SecurityScope {
    /** Empty when the request is in no app. */
    val appId: String

    /** Empty for the default space. */
    val spaceId: String

    /** The principal's roles in [spaceId], in claim order. */
    val roles: List<String>
}

/**
 * What the gateway in front of this service says about the request, in a service behind one
 * (`cosec.pipeline.mode=gateway`).
 */
class ForwardedIdentity(
    /** The gateway's `CoSec-Request-Id`, to correlate this request with the gateway's audit record. */
    val gatewayRequestId: String,
    /** The id of the token or API key the caller authenticated with at the gateway, never its value. */
    val credentialId: String,
)

/**
 * Who the current request is and in what scope, as the pipeline decided it. Read-only: an application reads it, and
 * nothing it could set reaches a decision.
 *
 * @see CoSecPrincipal
 */
interface SecurityContext {
    /** The tenant the request is in, [DEFAULT_TENANT_ID] for the default one. */
    val tenantId: String

    /** The principal making the request. */
    val principal: CoSecPrincipal

    /** The scope the request was authorized in, as `ScopeResolver` validated it (RFC 0004 X6). */
    val scope: SecurityScope

    /** Null unless this service sits behind a gateway that forwarded the caller's identity. */
    val forwardedIdentity: ForwardedIdentity?

    /**
     * The id of the request as CoSec used it in its audit records, spans and problem responses: the client's
     * `CoSec-Request-Id` when it sent an acceptable one, else the one CoSec generated, which no request header holds.
     * Empty for a context the pipeline did not make.
     */
    val requestId: String get() = ""

    /**
     * The device the client declared in `CoSec-Device-Id`, empty when it declared none. Declared by the client; CoSec
     * checks its form only, so it is not proof of a device.
     */
    val deviceId: String get() = ""

    companion object {
        /** The request attribute an adapter stores the context under. */
        const val KEY = "COSEC_SECURITY_CONTEXT"

        /**
         * The [tenantId] of the default tenant, the one a principal without a tenant is in, and an anonymous caller's.
         * It is spelled the same as the anonymous principal's id, `CoSecPrincipal.ANONYMOUS_ID`, and does not collide
         * with it: one names a tenant, the other a principal, and they are never compared. It is the same value as
         * `TenantId.DEFAULT` of `cosec-model`.
         */
        const val DEFAULT_TENANT_ID: String = "(0)"
    }
}
