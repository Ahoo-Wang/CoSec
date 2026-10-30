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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.engine.pattern.ResourceTarget
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.Attribute.Kind
import me.ahoo.cosec.model.condition.CheckResult
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.model.matches

/** Who asks, as the data plane sees it: always authenticated here. [attributes] are of any JSON type, as in a token. */
class OracleSubject(
    val tenant: TenantId,
    val id: String,
    val memberships: Memberships,
    val attributes: Map<String, Any>,
)

/** One concrete request. */
class OracleRequest(
    val subject: OracleSubject,
    val app: AppId,
    val space: SpaceId,
    val action: Action,
    val resource: Resource,
    val ip: IpAddress?,
    val headers: Map<String, String>,
) {
    /** The same request asked by [other] (RFC 0007 §7.4.2 `r[s := g]`). */
    fun by(other: OracleSubject) = OracleRequest(other, app, space, action, resource, ip, headers)

    override fun toString(): String =
        "${subject.id}@${app.value}/${space.value} ${action.namespace}:${action.verb} ${resource.type}:${resource.id} ip=$ip $headers"
}

/**
 * Which captures a statement with several matching resources reads. The engine reads one of them; which one is not
 * the analysis's to rely on, so the soundness properties check the strictest reading on the side that must be
 * permitted and the most permissive one on the side that was granted.
 */
enum class CaptureReading {
    /** Matches if the condition holds for the captures of some matching resource. */
    ANY,

    /** Matches if the condition holds for the captures of every matching resource. */
    ALL,

    /** The captures of the first matching resource. */
    FIRST,
}

enum class OracleDecision { PERMIT, DENY, INDETERMINATE }

/**
 * An independent transcription of the data plane over [GrantSnapshot]s, for the soundness properties A5, A6 and A8:
 * bindings applied as RFC 0004 §4.2 says and only until they expire, deny before allow, a binding or attachment to a
 * policy that does not resolve fails closed, and so does a tenant binding or attachment of an id the platform also
 * defines, unless both are the same policy (RFC 0007 N2). Patterns and conditions are matched and evaluated by the
 * engine and the model; only the combination is written here, without any of the analysis's code. Subject attributes
 * read as the engine reads them, as their `toString()`.
 */
class GrantOracle(
    private val allowReading: CaptureReading,
    private val denyReading: CaptureReading,
    /** Leave out what binds every subject alike: DENYs and unresolved policies reached through `Anyone` (Q8). */
    private val ignoreAnyone: Boolean,
    /**
     * Whether a binding or attachment of an ambiguous id fails, as on the data plane, or is left out, as if it were
     * not there: what the documents grant once the ambiguity is resolved, before anything re-points it.
     */
    private val ambiguousFails: Boolean = true,
) {
    private val checker = ConditionChecker(ConditionLimits(maxNodes = 129, maxDepth = 9, maxCost = 513))

    fun decide(snapshot: GrantSnapshot, request: OracleRequest): OracleDecision {
        var allowed = false
        var failed = false
        for ((policy, anyone) in applicable(snapshot, request)) {
            if (policy == null) {
                if (!(anyone && ignoreAnyone)) failed = true
                continue
            }
            when (effect(policy, request, denies = !(anyone && ignoreAnyone))) {
                Effect.DENY -> return OracleDecision.DENY
                Effect.ALLOW -> allowed = true
                null -> Unit
            }
        }
        return when {
            failed -> OracleDecision.INDETERMINATE
            allowed -> OracleDecision.PERMIT
            else -> OracleDecision.DENY
        }
    }

    /** DENY if a DENY of [policy] matches (and [denies] count), else ALLOW if an ALLOW does, else `null`. */
    private fun effect(policy: GrantPolicy, request: OracleRequest, denies: Boolean): Effect? {
        var allowed = false
        for (statement in policy.statements) {
            if (statement.effect == Effect.DENY && !denies) continue
            val condition = both(policy.condition, statement.condition)
            val reading = if (statement.effect == Effect.DENY) denyReading else allowReading
            if (matches(statement, condition, request, reading)) {
                if (statement.effect == Effect.DENY) return Effect.DENY
                allowed = true
            }
        }
        return if (allowed) Effect.ALLOW else null
    }

    /** The policies that apply to the request, `null` for one that does not resolve, and whether through `Anyone`. */
    private fun applicable(snapshot: GrantSnapshot, request: OracleRequest): List<Pair<GrantPolicy?, Boolean>> {
        val found = ArrayList<Pair<GrantPolicy?, Boolean>>()
        val tenant = snapshot.tenant(request.subject.tenant)
        val app = snapshot.apps[request.app]
        fun scan(document: GrantDocument, lookup: (PolicyId) -> GrantPolicy?) {
            for (binding in document.bindings) {
                if (applies(binding, snapshot.now, request) && binding.policy.value !in BUILTIN) {
                    found += lookup(binding.policy) to (binding.subject == SubjectRef.Anyone)
                }
            }
        }
        scan(snapshot.platform) { snapshot.platform.policy(it) }
        app?.let { document -> scan(document) { document.policy(it) ?: snapshot.platform.policy(it) } }
        scan(tenant) { shared(tenant, snapshot.platform, it) ?: app?.policy(it) }
        for (id in request.subject.memberships.attachments) {
            found += shared(tenant, snapshot.platform, id)?.takeIf { it.attachable } to false
        }
        return found
    }

    /**
     * The tenant's policy [id], else the platform's. When both define it as different policies (RFC 0007 N2), nothing,
     * which fails; or, unless [ambiguousFails], a policy that does nothing.
     */
    private fun shared(tenant: GrantDocument, platform: GrantDocument, id: PolicyId): GrantPolicy? {
        val own = tenant.policy(id)
        val other = platform.policy(id)
        return when {
            own == null || other == null || own === other -> own ?: other
            ambiguousFails -> null
            else -> LEFT_OUT
        }
    }

    private fun applies(binding: Binding, now: Long, request: OracleRequest): Boolean {
        if (binding.expiresAt != null && now >= binding.expiresAt!!) return false
        if (!binding.scope.app.matches(request.app) || !binding.scope.space.matches(request.space)) return false
        val subject = request.subject
        return when (val ref = binding.subject) {
            SubjectRef.Anyone -> true
            is SubjectRef.Principal -> ref.tenant == subject.tenant && ref.id.value == subject.id
            is SubjectRef.Role -> (ref.tenant == null || ref.tenant == subject.tenant) &&
                subject.memberships.roles[request.space].orEmpty().contains(ref.id)
        }
    }

    fun matches(statement: GrantStatement, condition: Condition?, request: OracleRequest, reading: CaptureReading): Boolean {
        if (statement.actions.none { it.matches(request.action) }) return false
        val captures = statement.resources.mapNotNull { pattern ->
            try {
                pattern.match(ResourceTarget(request.resource))?.toMap()
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        if (captures.isEmpty()) return false
        if (condition == null) return true
        val checked = (checker.check(condition) as CheckResult.Valid).condition
        fun holds(variables: Map<String, String>) = checked.evaluate(Input(request, variables))
        return when (reading) {
            CaptureReading.ANY -> captures.any(::holds)
            CaptureReading.ALL -> captures.all(::holds)
            CaptureReading.FIRST -> holds(captures.first())
        }
    }

    private class Input(private val request: OracleRequest, private val variables: Map<String, String>) : ConditionInput {
        private val subject = request.subject

        override fun string(attribute: Attribute): String? = when (attribute.kind) {
            Kind.SUBJECT_ID -> subject.id
            Kind.SUBJECT_TENANT, Kind.SCOPE_TENANT -> subject.tenant.value
            Kind.SUBJECT_ATTRIBUTE -> subject.attributes[attribute.name]?.toString()
            Kind.SCOPE_APP -> request.app.value
            Kind.SCOPE_SPACE -> request.space.value
            Kind.ACTION_SERVICE -> request.action.namespace
            Kind.ACTION_VERB -> request.action.verb
            Kind.RESOURCE_TYPE -> request.resource.type
            Kind.RESOURCE_ID -> request.resource.id
            Kind.RESOURCE_ATTRIBUTE -> request.resource.attributes[attribute.name]
            Kind.RESOURCE_VAR -> variables[attribute.name]
            Kind.HTTP_HEADER -> request.headers[attribute.name]
            else -> null
        }

        override fun bool(attribute: Attribute): Boolean? = when (attribute.kind) {
            Kind.SUBJECT_AUTHENTICATED -> true
            Kind.ENVIRONMENT_BLOCKED -> false
            else -> null
        }

        override fun stringSet(attribute: Attribute): Set<String>? = when (attribute.kind) {
            Kind.SUBJECT_ROLES -> subject.memberships.roles.values.flatMapTo(
                HashSet()
            ) { roles -> roles.map { it.value } }
            Kind.SUBJECT_SCOPED_ROLES -> subject.memberships.roles.flatMapTo(HashSet()) { (space, roles) ->
                roles.map { if (space == SpaceId.DEFAULT) it.value else "${it.value}@${space.value}" }
            }

            Kind.SCOPE_ROLES -> subject.memberships.roles[request.space].orEmpty().mapTo(HashSet()) { it.value }
            else -> null
        }

        override fun ip(attribute: Attribute): IpAddress? = if (attribute.kind == Kind.ENVIRONMENT_IP) request.ip else null

        override fun time(attribute: Attribute): Long = ConditionInput.NO_TIME
    }

    private companion object {
        val BUILTIN = setOf("cosec:blacklist", "cosec:root")

        /** An ambiguous id left out: no statement, so it neither allows nor denies, and it attaches without failing. */
        val LEFT_OUT = GrantPolicy(PolicyId("left-out"), emptyList(), attachable = true)
    }
}
