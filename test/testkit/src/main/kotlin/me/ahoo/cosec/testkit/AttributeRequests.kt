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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.spi.attribute.AttributeRequest

/**
 * The [AttributeRequest] an `AttributeProvider` is asked with, for the provider's own unit tests: the subject and scope
 * as the pipeline builds them for [principal] (a [PipelineFixture.Principal]; null for an anonymous request).
 *
 * Only the pipeline constructs a resolved scope, so the scope here is resolved by the pipeline's own resolver, from the
 * principal's roles, the [space] the request selects (`CoSec-Space-Id`, empty for the default space) and the app of the
 * host ([hostApp], `cosec.authorization.scope.app-id`). A request whose scope the pipeline would reject never reaches a
 * stage-B provider, so [of] throws for one; [beforeScope] is the request of stage A, which has no scope yet.
 */
@ExperimentalCoSecApi
object AttributeRequests {
    private const val REMOTE_ADDRESS = "10.0.0.1"
    private const val REQUEST_ID = "testkit"

    /** A stage-B request: the scope resolved. Throws [IllegalArgumentException] when the pipeline would reject it. */
    // stays: a stage-B request names each fact a scenario can set
    @Suppress("LongParameterList")
    fun of(
        action: Action,
        resource: Resource,
        principal: PipelineFixture.Principal? = null,
        space: String = "",
        hostApp: String? = null,
        remoteIp: String = REMOTE_ADDRESS,
        deviceId: String = "",
        requestId: String = REQUEST_ID,
    ): AttributeRequest {
        val tenant = principal?.tenant() ?: TenantId.DEFAULT
        val memberships = principal?.memberships() ?: Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())
        val subject = ScopeSubject(tenant, principal != null, memberships, principal?.app)
        val scope = when (val resolution = ScopeResolver.resolve(subject, space, HostScope(hostApp?.let(::AppId)))) {
            is ScopeResolution.Resolved -> resolution.scope
            is ScopeResolution.Rejected -> throw IllegalArgumentException(
                "The pipeline rejects this request's scope (${resolution.reason}): no stage-B provider is asked."
            )
        }
        return AttributeRequest(tenant, subject(principal), scope, action, resource, remoteIp, requestId, deviceId)
    }

    /** A stage-A request: asked before the scope is resolved, so its scope is null. */
    fun beforeScope(
        action: Action,
        resource: Resource,
        principal: PipelineFixture.Principal? = null,
        remoteIp: String = REMOTE_ADDRESS,
        deviceId: String = "",
        requestId: String = REQUEST_ID,
    ): AttributeRequest = AttributeRequest(
        principal?.tenant() ?: TenantId.DEFAULT,
        subject(principal),
        null,
        action,
        resource,
        remoteIp,
        requestId,
        deviceId,
    )

    /** As the pipeline makes it from an authenticated principal: a token's, so its bindings apply. */
    private fun subject(principal: PipelineFixture.Principal?) = Subject(
        id = principal?.id ?: CoSecPrincipal.ANONYMOUS_ID,
        authenticated = principal != null,
        attributes = principal?.attributes.orEmpty(),
        principalBindings = true,
        breakGlass = true,
    )
}
