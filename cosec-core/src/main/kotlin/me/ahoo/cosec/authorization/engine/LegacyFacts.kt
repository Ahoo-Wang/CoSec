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
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.HostFacts
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.Scope
import me.ahoo.cosec.model.Subject

/**
 * What the adapted legacy matchers need and the engine never reads: the original [request] and the
 * statement-scoped [context] over the caller's. One per evaluation.
 *
 * It also remembers the rules that matched, so the decision can be mapped back to the
 * [me.ahoo.cosec.api.policy.Policy], [me.ahoo.cosec.api.policy.Statement] and [me.ahoo.cosec.api.permission.Permission]
 * objects the audit and tracing decorators read. An untraced evaluation stops each layer at its first match, so it
 * records at most one rule per layer.
 */
internal class LegacyFacts(val request: Request, val base: SecurityContext) : HostFacts {
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

        /*
         * Phase 2 (RFC 0002 §3.3): the engine reads only `environment.blocked` and `subject.root`, so the rest of
         * the engine's request is left empty rather than read from the request. Reading it would differ from 7.0,
         * which reads the app, the space and the principal's policies and roles only when a layer needs them, and
         * the device, remote address and request id never; the layers read them from [request] and [context]
         * instead (PolicySetLoader), in 7.0's order and under its conditions, before evaluation (RFC 0002 X1).
         * Phase 4 fills these in when conditions move into the engine.
         */
        private val UNREAD_SUBJECT = Subject("", "", false, emptySet(), emptySet(), emptyMap(), root = false)
        private val UNREAD_SCOPE = Scope.declared("", "", "")
        private val UNREAD_ACTION = Action(HTTP, "")
        private val UNREAD_RESOURCE = Resource(HTTP, "", emptyMap())
        private val NOT_BLOCKED = Environment("", "", "", blocked = false)

        /** The engine's view of a request that is neither blocked nor root: nothing is read from [request] here. */
        fun authzRequest(request: Request, context: SecurityContext): AuthzRequest = AuthzRequest(
            subject = UNREAD_SUBJECT,
            scope = UNREAD_SCOPE,
            action = UNREAD_ACTION,
            resource = UNREAD_RESOURCE,
            environment = NOT_BLOCKED,
            host = LegacyFacts(request, context),
        )

        /**
         * A blocked request. The engine decides it before reading anything else ([me.ahoo.cosec.engine.Engine.requiresPolicies]
         * is false).
         */
        val BLOCKED = AuthzRequest(
            UNREAD_SUBJECT,
            UNREAD_SCOPE,
            UNREAD_ACTION,
            UNREAD_RESOURCE,
            Environment("", "", "", blocked = true),
        )

        /** A root principal's request that is not blocked; like [BLOCKED], decided before anything else is read. */
        val ROOT = AuthzRequest(
            Subject("", "", false, emptySet(), emptySet(), emptyMap(), root = true),
            UNREAD_SCOPE,
            UNREAD_ACTION,
            UNREAD_RESOURCE,
            NOT_BLOCKED,
        )
    }
}
