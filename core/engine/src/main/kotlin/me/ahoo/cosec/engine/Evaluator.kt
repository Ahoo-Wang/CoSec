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

package me.ahoo.cosec.engine

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.pattern.Captures
import me.ahoo.cosec.engine.pattern.EvaluationScratch
import me.ahoo.cosec.engine.pattern.HttpPath
import me.ahoo.cosec.engine.pattern.ResourceTarget
import me.ahoo.cosec.engine.pattern.ScratchSource
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import java.util.IdentityHashMap

/**
 * One evaluation over bindings: the flat algorithm of RFC 0004 §6.1 over compiled statements (RFC 0005 §6).
 *
 * 1. The DENY pass visits every candidate's DENY statements. The first match is the decision: a DENY wins over errors,
 *    missing policies and documents that could not be read.
 * 2. Without a match, an erroring DENY (statement or the policy condition reached through it) makes the decision
 *    indeterminate, and so does anything unresolved: there could be a DENY nobody could check.
 * 3. Only then the ALLOW pass: the first match permits; an erroring ALLOW counts as no match (C1a); nothing matched is
 *    an implicit deny. Its candidates are the DENY pass's, less a capped credential's attachments and principal
 *    bindings ([CandidateOrder]): a cap removes ALLOWs only.
 * 4. A Permit is held to the tenant's platform boundary, if any (RFC 0007 §7.6, [withinBoundary]): it stays a Permit
 *    only if a boundary statement matches. The boundary can only turn a Permit into a Deny or an Indeterminate (P-B2).
 *
 * So the decision depends on the set of candidates, never on their order (P8): only which statement is reported does.
 * A candidate policy's statements are those its index gives (I1), each evaluated by [step].
 *
 * Untraced, it stops at the decision and records nothing (`ALG-7`). Besides itself and the decision, it allocates
 * only for index lookups that merge several partitions, captures a condition reads, policies with a condition (a small
 * cache, so each condition runs at most once), the role sets and address a condition reads, and on error paths. It is
 * the [CandidateVisitor] of its own enumeration, the [ConditionInput] of every condition and the [IndexLookup] of every
 * index, so none of those allocate. [TracingEvaluator] overrides only [candidate], the step per candidate, so no
 * trace code sits in the untraced one (see `Evaluator` for why).
 *
 * The state and the algorithm stay here, so that deny-first (`ALG-1`) and the boundary's rule (P-B2) are reviewed in
 * one place; the rest are extension functions in the `Evaluator*.kt` files (RFC 0013 §6), static calls that add no
 * object. The state is `@JvmField internal`, so that they read it with a field access and no accessor call.
 */
// stays: most of its functions are the overrides of the four interfaces it implements, so that nothing allocates
@Suppress("TooManyFunctions")
internal open class Evaluator(
    @JvmField internal val request: EngineRequest,
    @JvmField internal val view: SnapshotView
) :
    CandidateVisitor,
    ConditionInput,
    IndexLookup,
    ScratchSource {
    @JvmField internal val authz: AuthzRequest = request.request

    @JvmField internal val host: ConditionInput = request.attributes

    @JvmField internal val target: ResourceTarget = ResourceTarget(authz.resource)

    /** The effect whose statements the current pass evaluates. */
    @JvmField internal var pass: Effect = Effect.DENY

    /** The pass's first match. */
    @JvmField internal var matched: Decision? = null

    // The first error of the DENY pass.
    @JvmField internal var errorAt: StatementRef.Policy? = null

    @JvmField internal var errorInGuard = false

    @JvmField internal var error: Throwable? = null

    /** The first ALLOW skipped because an external attribute was missing (RFC 0006 K12). */
    @JvmField internal var skipped: Cause? = null

    @JvmField internal var missing: MutableSet<PolicyId>? = null

    @JvmField internal var ambiguous: MutableSet<PolicyId>? = null

    // Policy conditions evaluated so far, by identity, and their results; allocated on the first condition. Up to
    // SCAN_GUARDS they are found by scanning these arrays, which allocates nothing and costs less than hashing for the few
    // a request usually reaches. A request that reaches more moves them all to [guardMap], so that finding one never
    // costs more than a hash lookup however many it reaches: a scan would make P conditions cost P² steps (#1212).
    // Never by value (CheckedCondition.equals compares canonical text): two conditions with the same text can decide
    // differently, as an `exists` on one external path declared String by one document's catalog and Bool by another's,
    // or a `time` with another zone table; and comparing texts that differ only late costs their length, up to megabytes
    // for large `in` sets, on every lookup. Policies meant to share a condition share the instance instead.
    @JvmField internal var guards: Array<CheckedCondition?>? = null

    @JvmField internal var guardResults: IntArray? = null

    @JvmField internal var guardErrors: Array<Throwable?>? = null

    @JvmField internal var guardCount = 0

    /** Each condition's result, a small `Int`, or its error: the same facts as the arrays, once there are too many. */
    @JvmField internal var guardMap: IdentityHashMap<CheckedCondition, Any>? = null

    /** The error of the condition [guard] returned [GUARD_ERROR] for last. */
    @JvmField internal var guardError: Throwable? = null

    /** The error of the last [step] that returned [ERROR] or [GUARD_FAILED]. */
    @JvmField internal var stepError: Throwable? = null

    /** The captures of the statement whose condition is being evaluated. */
    @JvmField internal var captures: Captures? = null

    // The positions an index lookup collected, merged by [candidates]: the first list alone needs no buffer.
    @JvmField internal var firstList: IntArray? = null

    @JvmField internal var lists: Array<IntArray?>? = null

    @JvmField internal var listCount = 0

    /**
     * The glob table and the marks of repeated targets ([BoundEntry.repeat]), made when the evaluation first needs
     * either: an evaluation of documents with neither glob segments nor repeated targets makes none.
     */
    @JvmField internal var scratch: EvaluationScratch? = null

    @JvmField internal var pathState = PATH_UNPARSED

    @JvmField internal var subjectRoles: Set<String>? = null

    @JvmField internal var scopedRoles: Set<String>? = null

    @JvmField internal var scopeRoles: Set<String>? = null

    @JvmField internal var remoteIpParsed = false

    @JvmField internal var remoteIp: IpAddress? = null

    fun evaluate(): Decision {
        val decision = denyPass()
        if (decision != null) {
            return decision
        }
        pass = Effect.ALLOW
        enumerate()
        val permit = allowDecision()
        // The boundary first: a request it denies spends no rate limit permit.
        return withObligations(if (view.bounded) withinBoundary(permit) else permit)
    }

    /**
     * Set while [withObligations] decides a permit's obligations: they are part of the decision, so an evaluation that
     * records a trace catches their errors as the untraced one does, although a statement has already matched (`ALG-8`).
     */
    @JvmField
    internal var obligating: Boolean = false

    /** The error of the last [bucket] that returned null. */
    @JvmField internal var bucketError: Throwable? = null

    /**
     * [decision], held to the boundary of the request's tenant (RFC 0007 §7.6). Without a boundary it is returned as
     * it is, having read two fields. Otherwise it stays a Permit only if a statement of the boundary matches, as an
     * ALLOW would; else it is a Deny, or an Indeterminate when a boundary statement failed. The boundary is a property
     * of the request, not of which ALLOW matched, so it is checked once. A boundary the platform does not define is a
     * missing policy: the request fails closed.
     */
    internal fun withinBoundary(decision: Decision): Decision {
        if (decision !is Decision.Permit) {
            return decision
        }
        val id = view.boundaryOf(request.scope) ?: return decision
        val boundary = view.platform.boundaryPolicy(id)
            ?: return Decision.Indeterminate(Cause.MissingPolicies(setOf(PolicyId(id))))
        var failure: Throwable? = null
        for (statement in boundary.allows) {
            when (boundaryStep(boundary, statement)) {
                MATCHED -> return decision
                ERROR, GUARD_FAILED -> if (!lapses(statement, stepError)) failure = failure ?: stepError
                GUARD_MISS -> break
            }
        }
        return if (failure != null) {
            Decision.Indeterminate(Cause.BoundaryUnavailable(PolicyId(id), failure))
        } else {
            Decision.Deny(StatementRef.Boundary(PolicyId(id), request.scope.tenant))
        }
    }

    /** One boundary statement, as [step] evaluates an ALLOW. The trace records it. */
    internal open fun boundaryStep(
        boundary: CompiledPolicy,
        statement: CompiledStatement
    ): Int = step(boundary, statement)

    /**
     * The ALLOW pass's decision: its match, else `Indeterminate` when an ALLOW was skipped for a missing external
     * attribute (it might have permitted: a 503 to retry, not a 403), else the implicit deny.
     */
    internal fun allowDecision(): Decision =
        matched ?: skipped?.let { Decision.Indeterminate(it) } ?: CoSecEngine.IMPLICIT_DENY

    /** Runs the DENY pass and returns its decision, or null when the ALLOW pass decides. */
    internal fun denyPass(): Decision? {
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
    internal open fun enumerate(): Boolean = CandidateOrder.forEach(request, view, pass, this)

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
     * `ALG-9`: a pass evaluates a policy once for all the bindings of one document that reach it. The later ones can
     * change nothing: scope and expiry are checked per binding before this, a statement's result depends only on the
     * request and the policy ([step]), and the first error and the first skipped ALLOW are kept already. So a document
     * that binds one costly policy many times costs one evaluation, and a policy costs at most one per document.
     */
    override fun repeated(policy: CompiledPolicy, via: BindingRef.Bound, slot: Int): Boolean =
        firstThrough(repeatBit(via, slot)) && candidate(policy, via)

    /** The bit of [slot] of [via]'s document in the current pass. */
    internal fun repeatBit(via: BindingRef.Bound, slot: Int): Int {
        val before = when (via.document) {
            DocumentKind.PLATFORM -> 0
            DocumentKind.APP -> view.platform.repeats
            DocumentKind.TENANT -> view.platform.repeats + (view.app?.repeats ?: 0)
        }
        return 2 * (before + slot) + if (pass === Effect.DENY) 0 else 1
    }

    /** Whether [bit] was clear; sets it. */
    internal fun firstThrough(bit: Int): Boolean {
        val scratch = scratch()
        val words = scratch.marks ?: LongArray(repeatWords()).also { scratch.marks = it }
        val word = bit ushr WORD_SHIFT
        val mask = 1L shl bit
        if ((words[word] and mask) != 0L) {
            return false
        }
        words[word] = words[word] or mask
        return true
    }

    override fun scratch(): EvaluationScratch = scratch ?: EvaluationScratch().also { scratch = it }

    private fun repeatWords(): Int {
        val slots = view.platform.repeats + (view.app?.repeats ?: 0) + (view.tenant?.repeats ?: 0)
        return (2 * slots + WORD_BITS - 1) ushr WORD_SHIFT
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

    override val action: Action get() = authz.action

    override val resourceType: String get() = authz.resource.type

    override fun decodedPath(): HttpPath? = decodedPathOf()

    override fun indexValue(attribute: Attribute): String? = string(attribute)

    override fun add(positions: IntArray) = addOf(positions)

    override fun string(attribute: Attribute): String? = stringOf(attribute)

    override fun bool(attribute: Attribute): Boolean? = boolOf(attribute)

    override fun stringSet(attribute: Attribute): Set<String>? = stringSetOf(attribute)

    override fun ip(attribute: Attribute): IpAddress? = ipOf(attribute)

    override fun time(attribute: Attribute): Long =
        if (attribute.kind == Attribute.Kind.ENVIRONMENT_TIME) request.time else host.time(attribute)

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

    /** Whether an error thrown while evaluating is that statement's error rather than the evaluation's. */
    internal open fun catches(error: Throwable): Boolean = error is Exception

    internal companion object {
        // What [step] found.
        const val MATCHED = 0
        const val ACTION_MISS = 1
        const val CONDITION_MISS = 2
        const val RESOURCE_MISS = 3
        const val GUARD_MISS = 4
        const val GUARD_FAILED = 5
        const val ERROR = 6

        // What [visit] asks for next.
        internal const val NEXT_STATEMENT = 0
        internal const val NEXT_POLICY = 1
        internal const val DECIDED = 2

        internal const val GUARD_MATCHED = 1
        internal const val GUARD_MISSED = 2
        internal const val GUARD_ERROR = 3
        internal const val INITIAL_GUARDS = 4
        internal const val SCAN_GUARDS = 32
        internal const val MAP_GUARDS = 128
        internal const val NOT_REMEMBERED = 0
        internal const val INITIAL_LISTS = 8

        internal const val WORD_BITS = 64
        internal const val WORD_SHIFT = 6

        internal const val PATH_UNPARSED = 0
        internal const val PATH_PARSED = 1
        internal const val PATH_MALFORMED = 2

        @JvmField
        internal val EMPTY = IntArray(0)
    }
}
