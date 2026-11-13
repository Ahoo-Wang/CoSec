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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject

/** What the policy-shaped benchmarks of this package share: an authenticated request and `Anyone` bindings. */
internal fun benchmarkRequest(action: Action, resource: Resource, subjectId: String = "user"): FlatRequest {
    val memberships = Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())
    val subject = ScopeSubject(TenantId("acme"), authenticated = true, memberships)
    val resolution = ScopeResolver.resolve(subject, "", HostScope(null))
    val authz = AuthzRequest(
        subject = Subject(subjectId, true, emptyMap(), principalBindings = true, breakGlass = true),
        action = action,
        resource = resource,
        environment = Environment("127.0.0.1", "request", "device", blocked = false),
    )
    return FlatRequest(authz, (resolution as ScopeResolution.Resolved).scope, memberships, time = 0)
}

internal fun anyoneBinding(policy: CompiledPolicy) =
    Binding(BindingId("b-${policy.id}"), SubjectRef.Anyone, BindingScope(Match.Any, Match.Any), policy.policyId)

internal fun emptyTenant() = CompiledDocument.tenant(TenantId("acme"), emptyList(), emptyList())
