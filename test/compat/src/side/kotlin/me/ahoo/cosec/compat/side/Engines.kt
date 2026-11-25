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

package me.ahoo.cosec.compat.side

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.engine.CoSecEngine
import me.ahoo.cosec.engine.EngineRequest
import me.ahoo.cosec.engine.ExternalValues
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.Obligation
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * This version's engine over a platform document and a tenant document of `owner` (MIX-8, MIX-11): `document.decide`
 * answers what it decides for one request (`permit`, `deny`, `indeterminate`), and `limit.obligation` the counter a
 * rate limit rule obliges for a permitted one, `<ruleId> <bucket>`, which the limiter is then asked to take from.
 */
internal object Engines {
    val ALL: Map<String, (Map<String, String>) -> String> = mapOf(
        "document.decide" to { a ->
            val (namespace, verb) = a.getValue("action").split(':', limit = 2)
            when (decide(a.getValue("owner"), a.getValue("body"), a.getValue("principal"), Action(namespace, verb), a.getValue("resource"))) {
                is Decision.Permit -> "permit"
                is Decision.Deny -> "deny"
                else -> "indeterminate"
            }
        },
        "limit.obligation" to { a -> obligation(a.getValue("owner"), a.getValue("principal")) },
    )

    private const val PLATFORM =
        """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","policies":[]}"""

    private const val LIMITED =
        """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z",""" +
            """"policies":[{"id":"orders","statements":[{"effect":"allow","action":"order:read","resource":"order/*"}]}],""" +
            """"bindings":[{"id":"anyone","subject":"*","app":"*","space":"*","policy":"orders"}],""" +
            """"rateLimits":[{"id":"api","action":"order:read","resource":"order/*","key":"subject.id",""" +
            """"permitsPerSecond":0.1,"windowSeconds":60}]}"""

    /** One rule keyed by `subject.id` over `order:read` on `order/1` by [principal]. */
    private fun obligation(owner: String, principal: String): String {
        val decision = decide(owner, LIMITED, principal, Action("order", "read"), "order/1")
        val limit = (decision as Decision.Permit).obligations.filterIsInstance<Obligation.RateLimit>().single()
        return "${limit.ruleId} ${limit.bucket}"
    }

    /** [action] on [resource] (`http:/path`, or `type/id`) by [principal] of [owner], over [tenantBody]. */
    private fun decide(owner: String, tenantBody: String, principal: String, action: Action, resource: String): Decision {
        val compiler = PolicyDocumentCompiler()
        val platform = compiler.compile(SnapshotKey.Platform, SnapshotDocuments.read(PLATFORM.encodeToByteArray()))
        val tenant = compiler.compile(SnapshotKey.Tenant(owner), SnapshotDocuments.read(tenantBody.encodeToByteArray()))
        val memberships = Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())
        val resolution = ScopeResolver.resolve(ScopeSubject(TenantId(owner), true, memberships), "", HostScope(null))
        val scope = (resolution as ScopeResolution.Resolved).scope
        val subject = Subject(principal, true, emptyMap(), true, false)
        val (type, id) = if (resource.startsWith("http:")) "http" to resource.removePrefix("http:") else
            resource.substringBefore('/') to resource.substringAfter('/', "")
        val request = AuthzRequest(subject, action, Resource(type, id), Environment("", "", "", false))
        val input = object : ConditionInput {
            override fun string(attribute: Attribute): String? = principal.takeIf { attribute.toString() == "subject.id" }

            override fun bool(attribute: Attribute): Boolean? = null

            override fun stringSet(attribute: Attribute): Set<String>? = null

            override fun ip(attribute: Attribute): IpAddress? = null

            override fun time(attribute: Attribute): Long = ConditionInput.NO_TIME
        }
        val engineRequest = EngineRequest(request, scope, memberships, System.currentTimeMillis(), input, ExternalValues.NONE)
        return CoSecEngine.evaluate(engineRequest, SnapshotView(platform, null, tenant, emptyList()), TraceLevel.OFF)
            .decision
    }
}
