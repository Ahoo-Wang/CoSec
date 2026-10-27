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

import me.ahoo.cosec.engine.pattern.Captures
import me.ahoo.cosec.engine.pattern.HttpPath
import me.ahoo.cosec.engine.pattern.ResourceTarget
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.decision.Obligation
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef

/**
 * One evaluation over bindings: the flat algorithm of RFC 0004 §6.1 over compiled statements (RFC 0005 §6).
 *
 * 1. The DENY pass visits every candidate's DENY statements. The first match is the decision: a DENY wins over errors,
 *    missing policies and documents that could not be read.
 * 2. Without a match, an erroring DENY (statement or the policy condition reached through it) makes the decision
 *    indeterminate, and so does anything unresolved: there could be a DENY nobody could check.
 * 3. Only then the ALLOW pass: the first match permits; an erroring ALLOW counts as no match (C1a); nothing matched is
 *    an implicit deny.
 *
 * So the decision depends on the set of candidates, never on their order (P8): only which statement is reported does.
 * A candidate policy's statements are those its index gives (I1), each evaluated by [step].
 *
 * Untraced, it stops at the decision and records nothing (`ALG-7`). Besides itself and the decision, it allocates
 * only for index lookups that merge several partitions, captures a condition reads, policies with a condition (a small
 * cache, so each condition runs at most once), the role sets and address a condition reads, and on error paths. It is
 * the [CandidateVisitor] of its own enumeration, the [ConditionInput] of every condition and the [IndexLookup] of every
 * index, so none of those allocate. [FlatTracingEvaluator] overrides only [candidate], the step per candidate, so no
 * trace code sits in the untraced one (see `Evaluator` for why).
 */
@Suppress("TooManyFunctions")
internal open class FlatEvaluator(protected val request: FlatRequest, protected val view: SnapshotView) :
    CandidateVisitor,
    ConditionInput,
    IndexLookup {
    protected val authz: AuthzRequest = request.request
    private val host: ConditionInput = request.attributes
    protected val target: ResourceTarget = ResourceTarget(authz.resource)

    /** The effect whose statements the current pass evaluates. */
    protected var pass: Effect = Effect.DENY

    /** The pass's first match. */
    protected var matched: Decision? = null

    // The first error of the DENY pass.
    private var errorAt: StatementRef.Policy? = null
    private var errorInGuard = false
    private var error: Throwable? = null

    /** The first ALLOW skipped because an external attribute was missing (RFC 0006 K12). */
    private var skipped: Cause? = null

    private var missing: MutableSet<PolicyId>? = null
    private var ambiguous: MutableSet<PolicyId>? = null

    // Policy conditions evaluated so far, by identity, and their results; allocated on the first condition.
    private var guards: Array<CheckedCondition?>? = null
    private var guardResults: IntArray? = null
    private var guardErrors: Array<Throwable?>? = null
    private var guardCount = 0

    /** The error of the condition [guard] returned [GUARD_ERROR] for last. */
    private var guardError: Throwable? = null

    /** The error of the last [step] that returned [ERROR] or [GUARD_FAILED]. */
    protected var stepError: Throwable? = null

    /** The captures of the statement whose condition is being evaluated. */
    private var captures: Captures? = null

    // The positions an index lookup collected, merged by [candidates]: the first list alone needs no buffer.
    private var firstList: IntArray? = null
    private var lists: Array<IntArray?>? = null
    private var listCount = 0

    private var pathState = PATH_UNPARSED
    private var subjectRoles: Set<String>? = null
    private var scopedRoles: Set<String>? = null
    private var scopeRoles: Set<String>? = null
    private var remoteIpParsed = false
    private var remoteIp: IpAddress? = null

    fun evaluate(): Decision {
        val decision = denyPass()
        if (decision != null) {
            return decision
        }
        pass = Effect.ALLOW
        enumerate()
        return withObligations(allowDecision())
    }

    /**
     * [decision] with its obligations, when it is a permit and a document of the view has rate limit rules (RFC 0006
     * §8.2): every rule that matches the request, in document order (platform, app, tenant) and rule order, with the
     * bucket its key reads. Matching is a statement's: action, resource and condition, each rule with its own captures.
     *
     * A rule whose condition or key reads an external attribute that could not be fetched has no bucket, and its
     * `strictFailure` decides. Any other error makes it strict: it cannot be skipped, so the pipeline refuses (503).
     */
    protected fun withObligations(decision: Decision): Decision {
        if (decision !is Decision.Permit || !view.limitsRates) {
            return decision
        }
        var obligations: ArrayList<Obligation>? = null
        for (document in RATE_LIMITED) {
            val rules = when (document) {
                DocumentKind.PLATFORM -> view.platform.rateLimits
                DocumentKind.APP -> view.app?.rateLimits
                DocumentKind.TENANT -> view.tenant?.rateLimits
            } ?: continue
            for (rule in rules) {
                val obligation = obligation(rule) ?: continue
                (obligations ?: ArrayList<Obligation>().also { obligations = it }) += obligation
            }
        }
        return obligations?.let { Decision.Permit(decision.by, it) } ?: decision
    }

    @Suppress("TooGenericExceptionCaught")
    private fun obligation(rule: CompiledRateLimit): Obligation? {
        val result = step(rule.policy, rule.statement)
        if (result == ERROR || result == GUARD_FAILED) {
            val error = stepError!!
            val missing = error is AttributeMissing
            if (missing && rule.statement.onMissing == OnMissing.NOMATCH) {
                return null
            }
            return Obligation.RateLimit(rule.id, null, rule.limit, rule.strictFailure || !missing)
        }
        if (result != MATCHED) {
            return null
        }
        val key = rule.key ?: return Obligation.RateLimit(rule.id, CompiledRateLimit.SHARED_BUCKET, rule.limit, rule.strictFailure)
        return try {
            // The statement captures only for its own condition: a key reading a variable matches with captures here.
            captures = if (key.kind == Attribute.Kind.RESOURCE_VAR) {
                rule.statement.resources.firstNotNullOfOrNull { it.match(target) }
            } else {
                null
            }
            val value = if (key.type == AttributeType.IP) ip(key)?.toString() else string(key)
            Obligation.RateLimit(rule.id, CompiledRateLimit.KEYED_BUCKET_PREFIX + value.orEmpty(), rule.limit, rule.strictFailure)
        } catch (error: Throwable) {
            if (!catches(error)) {
                throw error
            }
            Obligation.RateLimit(rule.id, null, rule.limit, rule.strictFailure || error !is AttributeMissing)
        } finally {
            captures = null
        }
    }

    /**
     * The ALLOW pass's decision: its match, else `Indeterminate` when an ALLOW was skipped for a missing external
     * attribute (it might have permitted: a 503 to retry, not a 403), else the implicit deny.
     */
    protected fun allowDecision(): Decision =
        matched ?: skipped?.let { Decision.Indeterminate(it) } ?: CoSecFlatEngine.IMPLICIT_DENY

    /** Runs the DENY pass and returns its decision, or null when the ALLOW pass decides. */
    protected fun denyPass(): Decision? {
        pass = Effect.DENY
        enumerate()
        matched?.let { return it }
        errorAt?.let { at ->
            val error = error!!
            val cause = if (error is AttributeMissing) {
                Cause.AttributeUnavailable(setOf(error.path), at)
            } else {
                Cause.MatcherFailed(at, errorInGuard, error)
            }
            return Decision.Indeterminate(cause)
        }
        return unresolved()?.let { Decision.Indeterminate(it) }
    }

    /** Visits the candidates. Open for the property test that shuffles them (P8). */
    protected open fun enumerate(): Boolean = CandidateOrder.forEach(request, view, this)

    /** The first of what the request could not resolve, in a fixed order: the caller's, then the engine's. */
    private fun unresolved(): Cause? =
        view.unresolved.firstOrNull()
            ?: view.notLoaded(request.scope)
            ?: missing?.let { Cause.MissingPolicies(it) }
            ?: ambiguous?.let { Cause.AmbiguousPolicy(it) }

    /** Everything unresolved, for the trace. */
    protected fun allUnresolved(): List<Cause> =
        buildList {
            addAll(view.unresolved)
            view.notLoaded(request.scope)?.let(::add)
            missing?.let { add(Cause.MissingPolicies(it)) }
            ambiguous?.let { add(Cause.AmbiguousPolicy(it)) }
        }

    override fun candidate(policy: CompiledPolicy, via: BindingRef): Boolean {
        val statements = policy.statements(pass)
        if (statements.isEmpty()) {
            return false
        }
        val index = policy.index(pass)
        if (index == null) {
            for (statement in statements) {
                val next = visit(policy, statement, via)
                if (next != NEXT_STATEMENT) {
                    return next == DECIDED
                }
            }
            return false
        }
        for (position in candidates(index)) {
            val next = visit(policy, statements[position], via)
            if (next != NEXT_STATEMENT) {
                return next == DECIDED
            }
        }
        return false
    }

    /**
     * Evaluates [statement] and acts on the result: [DECIDED] on a match, [NEXT_POLICY] when the policy condition does
     * not hold (none of the policy's statements can match), else [NEXT_STATEMENT].
     */
    private fun visit(policy: CompiledPolicy, statement: CompiledStatement, via: BindingRef): Int {
        when (step(policy, statement)) {
            MATCHED -> {
                matched = decide(policy, statement, via)
                return DECIDED
            }

            ERROR -> erred(policy, statement, via, stepError!!, guard = false)
            GUARD_FAILED -> {
                val error = stepError!!
                erred(policy, statement, via, error, guard = true)
                // A missing attribute that this statement treats as no match says nothing about the policy's others.
                return if (error is AttributeMissing && statement.onMissing == OnMissing.NOMATCH) NEXT_STATEMENT else NEXT_POLICY
            }

            GUARD_MISS -> return NEXT_POLICY
        }
        return NEXT_STATEMENT
    }

    /**
     * Evaluates [statement] of [policy]: its action, index keys, resource, the policy condition, then its condition,
     * stopping at the first that does not hold (see [CompiledStatement]). On [ERROR] and [GUARD_FAILED], [stepError]
     * is the error. An error that [catches] rejects propagates.
     *
     * The result depends only on the request, never on which statements were evaluated before: the policy condition's
     * result is cached, and a failed resource defers to it.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    protected fun step(policy: CompiledPolicy, statement: CompiledStatement): Int {
        if (!statement.matchesAction(authz.action)) {
            return ACTION_MISS
        }
        if (!statement.admits(this)) {
            return CONDITION_MISS
        }
        var failure: Throwable? = null
        val captures = try {
            resource(statement)
        } catch (thrown: Throwable) {
            if (!catches(thrown)) {
                throw thrown
            }
            failure = thrown
            null
        }
        if (captures == null && failure == null) {
            return RESOURCE_MISS
        }
        // Consulted even when the resource failed: a policy condition that does not hold rules out every statement of
        // the policy, so the untraced evaluation may skip the rest of them and still decide as the trace does.
        val guard = guardOf(policy)
        if (guard != MATCHED) {
            return guard
        }
        if (failure != null) {
            stepError = failure
            return ERROR
        }
        val condition = statement.condition ?: return MATCHED
        this.captures = captures
        return try {
            if (condition.evaluate(this)) MATCHED else CONDITION_MISS
        } catch (thrown: Throwable) {
            if (!catches(thrown)) {
                throw thrown
            }
            stepError = thrown
            ERROR
        } finally {
            this.captures = null
        }
    }

    /** [MATCHED] when [policy] has no condition or it holds, else [GUARD_MISS] or [GUARD_FAILED] with [stepError]. */
    private fun guardOf(policy: CompiledPolicy): Int {
        val guard = policy.condition ?: return MATCHED
        return when (guard(guard)) {
            GUARD_MISSED -> GUARD_MISS
            GUARD_ERROR -> {
                stepError = guardError
                GUARD_FAILED
            }

            else -> MATCHED
        }
    }

    /**
     * The captures of the first resource pattern that matches ([Captures.NONE] when the condition reads none), or
     * null. A pattern that fails (a path that does not decode) fails the statement unless another one matches.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun resource(statement: CompiledStatement): Captures? {
        var failure: Exception? = null
        for (pattern in statement.resources) {
            try {
                if (!statement.captures) {
                    if (pattern.matches(target)) {
                        return Captures.NONE
                    }
                } else {
                    pattern.match(target)?.let { return it }
                }
            } catch (error: Exception) {
                failure = failure ?: error
            }
        }
        if (failure != null) {
            throw failure
        }
        return null
    }

    /** Whether the index or the scan can skip [statement]: what [step] checks before calling host code. */
    protected fun ruledOut(statement: CompiledStatement): Int =
        when {
            !statement.matchesAction(authz.action) -> ACTION_MISS
            !statement.admits(this) -> CONDITION_MISS
            else -> RESOURCE_MISS
        }

    /** The positions [index] gives for this request, sorted. */
    protected fun candidates(index: StatementIndex): IntArray {
        listCount = 0
        index.lookup(this)
        val count = listCount
        if (count <= 1) {
            val first = firstList
            firstList = null
            return first ?: EMPTY
        }
        val lists = lists!!
        lists[0] = firstList
        firstList = null
        var total = 0
        for (i in 0 until count) {
            total += lists[i]!!.size
        }
        val merged = IntArray(total)
        var at = 0
        for (i in 0 until count) {
            val list = lists[i]!!
            list.copyInto(merged, at)
            at += list.size
            lists[i] = null
        }
        merged.sort()
        var size = 0
        for (i in merged.indices) {
            if (size == 0 || merged[size - 1] != merged[i]) {
                merged[size++] = merged[i]
            }
        }
        return if (size == merged.size) merged else merged.copyOf(size)
    }

    override val action: Action get() = authz.action

    override val resourceType: String get() = authz.resource.type

    @Suppress("SwallowedException")
    override fun decodedPath(): HttpPath? {
        if (pathState == PATH_UNPARSED) {
            pathState = try {
                target.httpPath(true)
                PATH_PARSED
            } catch (malformed: IllegalArgumentException) {
                // Matching the patterns fails the statements instead; the index only learns it cannot use the path.
                PATH_MALFORMED
            }
        }
        return if (pathState == PATH_PARSED) target.httpPath(true) else null
    }

    override fun indexValue(attribute: Attribute): String? = string(attribute)

    override fun add(positions: IntArray) {
        if (positions.isEmpty()) {
            return
        }
        if (listCount == 0) {
            firstList = positions
            listCount = 1
            return
        }
        var lists = lists
        if (lists == null) {
            lists = arrayOfNulls(INITIAL_LISTS)
            this.lists = lists
        } else if (listCount == lists.size) {
            lists = lists.copyOf(listCount * 2)
            this.lists = lists
        }
        lists[listCount++] = positions
    }

    /*
     * The attributes of the request (RFC 0005 §3.2). The engine answers what it holds itself: the subject, the scope
     * ScopeResolver checked and the bindings used, the action, the resource and the variables the statement captured,
     * the request time and the blacklist flag. A host input cannot contradict them, nor make up a captured variable.
     * The rest (headers, request attributes) comes from [FlatRequest.attributes].
     */

    override fun string(attribute: Attribute): String? =
        when (attribute.kind) {
            Attribute.Kind.SUBJECT_ID -> if (authz.subject.authenticated) authz.subject.id else null
            Attribute.Kind.SUBJECT_TENANT, Attribute.Kind.SCOPE_TENANT -> request.scope.tenant.value
            Attribute.Kind.SUBJECT_ATTRIBUTE -> authz.subject.attributes[attribute.name]?.toString()
            Attribute.Kind.SCOPE_APP -> request.scope.app.value.takeUnless { it.isEmpty() }
            Attribute.Kind.SCOPE_SPACE -> request.scope.space.value
            Attribute.Kind.ENVIRONMENT_DEVICE -> authz.environment.deviceId.takeUnless { it.isEmpty() }
            else -> target(attribute)
        }

    /** The action, the resource and the captures; the host for the rest. */
    private fun target(attribute: Attribute): String? =
        when (attribute.kind) {
            Attribute.Kind.ACTION_SERVICE -> authz.action.namespace
            Attribute.Kind.ACTION_VERB -> authz.action.verb
            Attribute.Kind.RESOURCE_TYPE -> authz.resource.type
            Attribute.Kind.RESOURCE_ID -> authz.resource.id
            Attribute.Kind.RESOURCE_ATTRIBUTE -> authz.resource.attributes[attribute.name]
            Attribute.Kind.RESOURCE_VAR -> captures?.get(attribute.name)
            Attribute.Kind.EXTERNAL -> request.external.read(attribute) as String?
            else -> host.string(attribute)
        }

    override fun bool(attribute: Attribute): Boolean? =
        when (attribute.kind) {
            Attribute.Kind.SUBJECT_AUTHENTICATED -> authz.subject.authenticated
            Attribute.Kind.ENVIRONMENT_BLOCKED -> authz.environment.blocked
            Attribute.Kind.EXTERNAL -> request.external.read(attribute) as Boolean?
            else -> host.bool(attribute)
        }

    override fun stringSet(attribute: Attribute): Set<String>? =
        when (attribute.kind) {
            Attribute.Kind.SUBJECT_ROLES -> subjectRoles ?: subjectRoles().also { subjectRoles = it }
            Attribute.Kind.SUBJECT_SCOPED_ROLES -> scopedRoles ?: scopedRoles().also { scopedRoles = it }
            Attribute.Kind.SCOPE_ROLES -> scopeRoles ?: request.scope.roles.mapTo(HashSet()) { it.value }
                .also { scopeRoles = it }

            @Suppress("UNCHECKED_CAST")
            Attribute.Kind.EXTERNAL
            -> request.external.read(attribute) as Set<String>?
            else -> host.stringSet(attribute)
        }

    override fun ip(attribute: Attribute): IpAddress? {
        if (attribute.kind == Attribute.Kind.EXTERNAL) {
            return request.external.read(attribute) as IpAddress?
        }
        if (attribute.kind != Attribute.Kind.ENVIRONMENT_IP) {
            return host.ip(attribute)
        }
        if (!remoteIpParsed) {
            remoteIp = IpAddress.parse(authz.environment.remoteIp)
            remoteIpParsed = true
        }
        return remoteIp
    }

    override fun time(attribute: Attribute): Long =
        if (attribute.kind == Attribute.Kind.ENVIRONMENT_TIME) request.time else host.time(attribute)

    /** Bare role ids in any space. */
    private fun subjectRoles(): Set<String> {
        val roles = HashSet<String>()
        for (spaceRoles in request.memberships.roles.values) {
            spaceRoles.mapTo(roles) { it.value }
        }
        return roles
    }

    /** As the token claims them: `role` in the default space, `role@space` elsewhere. */
    private fun scopedRoles(): Set<String> {
        val roles = HashSet<String>()
        for ((space, spaceRoles) in request.memberships.roles) {
            for (role in spaceRoles) {
                roles += if (space == SpaceId.DEFAULT) role.value else role.value + "@" + space.value
            }
        }
        return roles
    }

    override fun expired(via: BindingRef.Bound) = Unit

    override fun missing(policyId: String) {
        // Both passes enumerate the same candidates; the DENY pass records.
        if (pass === Effect.DENY) {
            (missing ?: LinkedHashSet<PolicyId>().also { missing = it }) += PolicyId(policyId)
        }
    }

    override fun ambiguous(policyId: String) {
        if (pass === Effect.DENY) {
            (ambiguous ?: LinkedHashSet<PolicyId>().also { ambiguous = it }) += PolicyId(policyId)
        }
    }

    /**
     * The result of [guard], evaluating it on first use; on [GUARD_ERROR], [guardError] is its error. An error that
     * [catches] rejects propagates.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun guard(guard: CheckedCondition): Int {
        val guards = guards
        if (guards != null) {
            for (i in 0 until guardCount) {
                if (guards[i] === guard) {
                    guardError = guardErrors?.get(i)
                    return guardResults!![i]
                }
            }
        }
        var error: Throwable? = null
        val result = try {
            if (guard.evaluate(this)) GUARD_MATCHED else GUARD_MISSED
        } catch (thrown: Throwable) {
            if (!catches(thrown)) {
                throw thrown
            }
            error = thrown
            GUARD_ERROR
        }
        remember(guard, result, error)
        guardError = error
        return result
    }

    private fun remember(guard: CheckedCondition, result: Int, error: Throwable?) {
        var guards = guards
        var results = guardResults
        if (guards == null || results == null) {
            guards = arrayOfNulls(INITIAL_GUARDS)
            results = IntArray(INITIAL_GUARDS)
        } else if (guardCount == guards.size) {
            guards = guards.copyOf(guardCount * 2)
            results = results.copyOf(guardCount * 2)
            guardErrors = guardErrors?.copyOf(guardCount * 2)
        }
        this.guards = guards
        guardResults = results
        if (error != null) {
            val errors = guardErrors ?: arrayOfNulls<Throwable>(guards.size).also { guardErrors = it }
            errors[guardCount] = error
        }
        guards[guardCount] = guard
        results[guardCount] = result
        guardCount++
    }

    /** Whether an error thrown while evaluating is that statement's error rather than the evaluation's. */
    protected open fun catches(error: Throwable): Boolean = error is Exception

    protected fun decide(policy: CompiledPolicy, statement: CompiledStatement, via: BindingRef): Decision {
        val ref = ref(policy, statement, via)
        return if (pass === Effect.DENY) Decision.Deny(ref) else Decision.Permit(ref)
    }

    /**
     * [statement], or the policy condition reached through it, failed with [error]. A missing external attribute is
     * decided by the statement's [OnMissing] (RFC 0006 §5.4.3): `nomatch` is a miss; otherwise a DENY errs, and an
     * ALLOW is skipped and remembered. Any other error of a DENY is kept; of an ALLOW, it is no match (C1a).
     */
    protected fun erred(
        policy: CompiledPolicy,
        statement: CompiledStatement,
        via: BindingRef,
        error: Throwable,
        guard: Boolean
    ) {
        val missing = error is AttributeMissing
        if (missing && statement.onMissing == OnMissing.NOMATCH) {
            return
        }
        if (pass === Effect.DENY) {
            failed(policy, statement, via, error, guard)
        } else if (missing && skipped == null) {
            skipped = Cause.AttributeUnavailable(setOf((error as AttributeMissing).path), ref(policy, statement, via))
        }
    }

    /** A DENY statement, or the condition reached through it, failed. Keeps the first. */
    protected fun failed(
        policy: CompiledPolicy,
        statement: CompiledStatement,
        via: BindingRef,
        error: Throwable,
        guard: Boolean,
    ) {
        if (errorAt == null) {
            errorAt = ref(policy, statement, via)
            errorInGuard = guard
            this.error = error
        }
    }

    protected fun ref(policy: CompiledPolicy, statement: CompiledStatement, via: BindingRef): StatementRef.Policy =
        StatementRef.Policy(PolicyId(policy.id), statement.index, statement.name, via)

    protected companion object {
        // What [step] found.
        const val MATCHED = 0
        const val ACTION_MISS = 1
        const val CONDITION_MISS = 2
        const val RESOURCE_MISS = 3
        const val GUARD_MISS = 4
        const val GUARD_FAILED = 5
        const val ERROR = 6

        // What [visit] asks for next.
        private const val NEXT_STATEMENT = 0
        private const val NEXT_POLICY = 1
        private const val DECIDED = 2

        private const val GUARD_MATCHED = 1
        private const val GUARD_MISSED = 2
        private const val GUARD_ERROR = 3
        private const val INITIAL_GUARDS = 4
        private const val INITIAL_LISTS = 8

        private const val PATH_UNPARSED = 0
        private const val PATH_PARSED = 1
        private const val PATH_MALFORMED = 2

        private val EMPTY = IntArray(0)
        private val RATE_LIMITED = arrayOf(DocumentKind.PLATFORM, DocumentKind.APP, DocumentKind.TENANT)
    }
}
