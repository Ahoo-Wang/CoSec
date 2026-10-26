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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.engine.GuardBehavior
import me.ahoo.cosec.engine.RuleBehavior
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef

/**
 * RFC 0004 §4.2 (`appliesTo`), §6.3 (candidate order) and §6.1 (the flat algorithm) transcribed over the raw bindings
 * of a [FlatCase], without the index, and reading the fixture statements' behaviors instead of evaluating them. A
 * statement matches when its action does, then the policy condition, then its condition (RFC 0005 §6.1): a policy
 * condition is reached only through a statement whose action matches. Policy ids resolve by RFC 0007 §3.1 N2: an id
 * the tenant and the platform both define is ambiguous, whatever else is true, unless both have the same content (then
 * it resolves to the tenant's copy).
 */
object Oracle {
    class Enumeration(
        val candidates: List<Candidate>,
        val expired: List<BindingRef.Bound>,
        val missing: Set<String>,
        val ambiguous: Set<String>,
    )

    private val byId = Comparator<Binding> { a, b -> a.id.value.compareTo(b.id.value) }
    private val byPolicy = Comparator<Binding> { a, b ->
        a.policy.value.compareTo(b.policy.value).takeIf { it != 0 } ?: byId.compare(a, b)
    }

    private fun appliesTo(binding: Binding, request: FlatRequest): Boolean {
        val scope = request.scope
        val subject = request.request.subject
        val subjectMatches = when (val s = binding.subject) {
            SubjectRef.Anyone -> true
            is SubjectRef.Principal -> subject.authenticated && s.tenant == scope.tenant && s.id.value == subject.id
            is SubjectRef.Role -> (s.tenant == null || s.tenant == scope.tenant) && s.id in scope.roles
        }
        val app = binding.scope.app
        val space = binding.scope.space
        return subjectMatches &&
            (app == Match.Any || app == Match.Exactly(scope.app)) &&
            (space == Match.Any || space == Match.Exactly(scope.space))
    }

    private class Builder(val case: FlatCase) {
        val candidates = mutableListOf(Candidate(BuiltinPolicies.blacklist, BindingRef.Builtin))
        val expired = mutableListOf<BindingRef.Bound>()
        val missing = linkedSetOf<String>()
        val ambiguous = linkedSetOf<String>()

        private fun RawDocument.defines(id: String) = policies.any { it.id == id }

        private fun RawDocument.find(id: String) = policies.find { it.id == id }

        fun applying(document: RawDocument, predicate: (Binding) -> Boolean) =
            document.bindings.filter { predicate(it) && appliesTo(it, case.request) }

        fun add(document: RawDocument, kind: DocumentKind, bindings: List<Binding>) {
            for (binding in bindings) {
                val via = BindingRef.Bound(binding.id, kind)
                if (case.request.time >= (binding.expiresAt ?: Long.MAX_VALUE)) {
                    expired += via
                    continue
                }
                val id = binding.policy.value
                if (kind == DocumentKind.TENANT && differs(document.find(id), case.platform.find(id))) {
                    ambiguous += id
                    continue
                }
                val policy = if (id == BuiltinPolicies.ROOT.value) {
                    BuiltinPolicies.root
                } else {
                    document.find(id) ?: case.platform.find(id)
                }
                if (policy == null) missing += id else candidates += Candidate(policy, via)
            }
        }

        /** Both layers define the id, with different content (RFC 0007 N2). */
        private fun differs(tenant: CompiledPolicy?, platform: CompiledPolicy?): Boolean =
            tenant != null && platform != null && !tenant.sameContent(platform)

        fun attach(tenant: RawDocument?, id: PolicyId) {
            val inTenant = tenant?.find(id.value)
            val inPlatform = case.platform.find(id.value)
            when {
                differs(inTenant, inPlatform) -> ambiguous += id.value
                inTenant == null && inPlatform == null -> missing += id.value
                else -> {
                    val policy = checkNotNull(inTenant ?: inPlatform)
                    if (policy.attachable) candidates += Candidate(policy, BindingRef.Attached(id)) else missing += id.value
                }
            }
        }
    }

    fun enumerate(case: FlatCase): Enumeration {
        val request = case.request
        val tenant = case.tenant.takeIf { case.tenantLoaded }
        val platform = case.platform
        val anyone = { b: Binding -> b.subject == SubjectRef.Anyone }
        val principal = { b: Binding -> b.subject is SubjectRef.Principal && !b.breakGlass }
        with(Builder(case)) {
            add(platform, DocumentKind.PLATFORM, applying(platform) { it.breakGlass }.sortedWith(byId))
            add(platform, DocumentKind.PLATFORM, applying(platform, anyone).sortedWith(byPolicy))
            tenant?.let { add(it, DocumentKind.TENANT, applying(it, anyone).sortedWith(byPolicy)) }
            if (request.request.subject.authenticated) {
                request.memberships.attachments.forEach { attach(tenant, it) }
                add(platform, DocumentKind.PLATFORM, applying(platform, principal).sortedWith(byId))
                tenant?.let { add(it, DocumentKind.TENANT, applying(it, principal).sortedWith(byId)) }
            }
            val app = case.app.takeIf { case.appLoaded }
            for ((document, kind) in listOf(app to DocumentKind.APP, tenant to DocumentKind.TENANT)) {
                if (document == null) continue
                for (role in request.scope.roles) {
                    add(document, kind, applying(document) { (it.subject as? SubjectRef.Role)?.id == role })
                }
            }
            return Enumeration(candidates, expired, missing, ambiguous)
        }
    }

    /** A statement of a candidate, as the oracle reads it. */
    private class Spec(val index: Int, val effect: Effect, val behavior: RuleBehavior)

    private fun statements(case: FlatCase, policy: CompiledPolicy): List<Spec> {
        val spec = case.fixture.spec(policy)
        if (spec != null) {
            return spec.statements.mapIndexed { i, (effect, behavior) -> Spec(i, effect, behavior) }
        }
        return when (policy) {
            BuiltinPolicies.blacklist -> {
                val behavior = if (case.request.request.environment.blocked) RuleBehavior.MATCH else RuleBehavior.CONDITION_MISS
                listOf(Spec(0, Effect.DENY, behavior))
            }

            BuiltinPolicies.root -> listOf(Spec(0, Effect.ALLOW, RuleBehavior.MATCH))
            else -> error("Unknown policy ${policy.id}")
        }
    }

    private fun ref(candidate: Candidate, statement: Spec) =
        StatementRef.Policy(
            PolicyId(candidate.policy.id),
            statement.index,
            candidate.policy.statements[statement.index].name,
            candidate.via
        )

    /** The first statement of [effect] that matches, the first error, or null; errors only for a DENY. */
    private sealed interface Outcome {
        class Matched(val ref: StatementRef.Policy) : Outcome

        class Failed(val cause: Cause.MatcherFailed) : Outcome
    }

    private fun evaluate(case: FlatCase, candidate: Candidate, effect: Effect): List<Outcome> {
        val guard = case.fixture.spec(candidate.policy)?.guard
        val outcomes = mutableListOf<Outcome>()
        for (statement in statements(case, candidate.policy).filter { it.effect == effect }) {
            if (statement.behavior == RuleBehavior.ACTION_MISS) continue
            when (guard?.behavior) {
                GuardBehavior.MISS -> return outcomes
                GuardBehavior.THROW -> {
                    outcomes += Outcome.Failed(Cause.MatcherFailed(ref(candidate, statement), true, guard.error))
                    return outcomes
                }

                else -> Unit
            }
            when (statement.behavior) {
                RuleBehavior.MATCH -> {
                    outcomes += Outcome.Matched(ref(candidate, statement))
                    return outcomes
                }

                RuleBehavior.THROW -> {
                    val error = case.fixture.error(candidate.policy, candidate.policy.statements[statement.index].name)
                    outcomes += Outcome.Failed(Cause.MatcherFailed(ref(candidate, statement), false, error))
                }

                else -> Unit
            }
        }
        return outcomes
    }

    /** The DENY pass: a matched DENY, else the first error, else null. */
    private fun denyPass(case: FlatCase, candidates: List<Candidate>): Decision? {
        var error: Cause.MatcherFailed? = null
        for (candidate in candidates) {
            for (outcome in evaluate(case, candidate, Effect.DENY)) {
                when (outcome) {
                    is Outcome.Matched -> return Decision.Deny(outcome.ref)
                    is Outcome.Failed -> error = error ?: outcome.cause
                }
            }
        }
        return error?.let { Decision.Indeterminate(it) }
    }

    private fun unresolved(case: FlatCase, missing: Set<String>, ambiguous: Set<String>): Cause? {
        val scope = case.request.scope
        val appNeeded = scope.roles.isNotEmpty() && scope.app.value.isNotEmpty()
        val notLoaded = when {
            !case.tenantLoaded -> Cause.NotLoaded(DocumentKind.TENANT)
            !case.appLoaded && appNeeded -> Cause.NotLoaded(DocumentKind.APP)
            else -> null
        }
        val missingPolicies = if (missing.isEmpty()) null else Cause.MissingPolicies(missing.map(::PolicyId).toSet())
        val ambiguousIds = ambiguous.map(::PolicyId).toSet()
        val ambiguousPolicies = if (ambiguousIds.isEmpty()) null else Cause.AmbiguousPolicy(ambiguousIds)
        return case.unresolved.firstOrNull() ?: notLoaded ?: missingPolicies ?: ambiguousPolicies
    }

    fun decide(case: FlatCase): Decision {
        val enumeration = enumerate(case)
        denyPass(case, enumeration.candidates)?.let { return it }
        unresolved(case, enumeration.missing, enumeration.ambiguous)?.let { return Decision.Indeterminate(it) }
        for (candidate in enumeration.candidates) {
            val match = evaluate(case, candidate, Effect.ALLOW).filterIsInstance<Outcome.Matched>().firstOrNull()
            if (match != null) return withinBoundary(case, Decision.Permit(match.ref))
        }
        return Decision.Deny(null)
    }

    /** Whether a statement of [boundary] matches, and the first error if none does. */
    private fun boundaryStatements(case: FlatCase, boundary: CompiledPolicy): Pair<Boolean, Throwable?> {
        val spec = case.fixture.spec(boundary)!!
        var error: Throwable? = null
        for ((index, statement) in spec.statements.withIndex()) {
            val behavior = statement.second
            if (behavior == RuleBehavior.ACTION_MISS) continue
            when (spec.guard?.behavior) {
                GuardBehavior.MISS -> return false to error
                GuardBehavior.THROW -> {
                    error = error ?: spec.guard!!.error
                    continue
                }

                else -> Unit
            }
            when (behavior) {
                RuleBehavior.MATCH -> return true to null
                RuleBehavior.THROW -> error = error ?: case.fixture.error(boundary, "${boundary.id}.$index")
                else -> Unit
            }
        }
        return false to error
    }

    /**
     * RFC 0007 §7.6, as written: the tenant's boundary, else the platform default, none for the platform tenant; any
     * boundary statement MATCH keeps the Permit, else any ERROR is Indeterminate, else Deny.
     */
    private fun withinBoundary(case: FlatCase, permit: Decision.Permit): Decision {
        val tenant = case.request.scope.tenant
        if (tenant == TenantId.PLATFORM) return permit
        val id = case.tenantBoundary ?: case.defaultBoundary ?: return permit
        val boundary = case.boundaries.firstOrNull { it.id == id }
            ?: return Decision.Indeterminate(Cause.MissingPolicies(setOf(PolicyId(id))))
        val (matched, error) = boundaryStatements(case, boundary)
        if (matched) return permit
        return if (error != null) {
            Decision.Indeterminate(Cause.BoundaryUnavailable(PolicyId(id), error))
        } else {
            Decision.Deny(StatementRef.Boundary(PolicyId(id), tenant))
        }
    }
}
