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

package me.ahoo.cosec.authorization.engine

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.context.tenant
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.HostFacts
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.Scope
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.policy.condition.part.ResourceParts

/**
 * What the adapted legacy matchers need and the engine never reads: the original [request] and the
 * statement-scoped [context] over the caller's. One per evaluation.
 *
 * It also remembers the rules that matched, so the decision can be mapped back to the
 * [me.ahoo.cosec.api.policy.Policy], [me.ahoo.cosec.api.policy.Statement] and [me.ahoo.cosec.api.permission.Permission]
 * objects the audit and tracing decorators read. An untraced evaluation stops each layer at its first match, so it
 * records at most one rule per layer.
 */
internal class LegacyFacts(val request: Request, base: SecurityContext) : HostFacts {
    val context = StatementScopedContext(base)

    private var matched0: StatementRule? = null
    private var matched1: StatementRule? = null
    private var matched2: StatementRule? = null
    private var moreMatched: ArrayList<StatementRule>? = null

    /** Called before every guard and rule. */
    fun beginStatement(): StatementScopedContext {
        context.beginStatement()
        return context
    }

    fun ruleMatched(rule: StatementRule) {
        when {
            matched0 == null -> matched0 = rule
            matched1 == null -> matched1 = rule
            matched2 == null -> matched2 = rule
            else -> (moreMatched ?: ArrayList<StatementRule>().also { moreMatched = it }).add(rule)
        }
    }

    /** The first recorded rule [predicate] accepts. */
    inline fun firstMatched(predicate: (StatementRule) -> Boolean): StatementRule? {
        matched0?.takeIf(predicate)?.let { return it }
        matched1?.takeIf(predicate)?.let { return it }
        matched2?.takeIf(predicate)?.let { return it }
        return moreMatched?.firstOrNull(predicate)
    }

    companion object {
        const val HTTP = "http"

        /**
         * The engine's view of [request] (RFC 0002 §3.3). Read only for a request that is neither blocked nor root,
         * as 7.0 reads it only then.
         */
        fun authzRequest(request: Request, context: SecurityContext): AuthzRequest {
            val principal = context.principal
            val subject = Subject(
                id = principal.id,
                tenantId = principal.tenant.tenantId,
                authenticated = principal.authenticated,
                roles = principal.roles,
                policies = principal.policies,
                attributes = principal.attributes,
                root = false,
            )
            return AuthzRequest(
                subject = subject,
                scope = Scope.declared(context.tenant.tenantId, request.appId, request.spaceId),
                action = Action(HTTP, request.method),
                resource = Resource(HTTP, request.path, ResourceAttributes.of(request.attributes)),
                environment = Environment(request.remoteIp, request.requestId, request.deviceId, blocked = false),
                host = LegacyFacts(request, context),
            )
        }

        private val NOBODY = Subject("", "", false, emptySet(), emptySet(), emptyMap(), root = false)
        private val NOWHERE = Scope.declared("", "", "")
        private val NO_ACTION = Action(HTTP, "")
        private val NO_RESOURCE = Resource(HTTP, "", emptyMap())

        /**
         * A blocked request. The engine decides it before reading anything else ([me.ahoo.cosec.engine.Engine.requiresPolicies]
         * is false), so the request is not read at all, as 7.0 does not read it.
         */
        val BLOCKED = AuthzRequest(NOBODY, NOWHERE, NO_ACTION, NO_RESOURCE, Environment("", "", "", blocked = true))

        /** A root principal's request that is not blocked; like [BLOCKED], decided before anything else is read. */
        val ROOT = AuthzRequest(
            Subject("", "", false, emptySet(), emptySet(), emptyMap(), root = true),
            NOWHERE,
            NO_ACTION,
            NO_RESOURCE,
            Environment("", "", "", blocked = false),
        )
    }
}

/** The request attributes named `resource.<name>`, by name, filtered when first read. */
internal class ResourceAttributes private constructor(private val requestAttributes: Map<String, String>) :
    AbstractMap<String, String>() {
    private var filtered: Map<String, String>? = null

    override val entries: Set<Map.Entry<String, String>>
        get() = (filtered ?: filter().also { filtered = it }).entries

    private fun filter(): Map<String, String> = buildMap {
        for ((key, value) in requestAttributes) {
            if (key.startsWith(ResourceParts.PREFIX)) {
                put(key.substring(ResourceParts.PREFIX.length), value)
            }
        }
    }

    companion object {
        fun of(requestAttributes: Map<String, String>): Map<String, String> =
            if (requestAttributes.isEmpty()) emptyMap() else ResourceAttributes(requestAttributes)
    }
}
