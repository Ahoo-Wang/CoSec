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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.document.DocumentCompileException
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/** The limits of what a change may publish (RFC 0007 §6.2 V2), and how changes expire and are approved. */
class ChangeOptions(
    /** RFC 0003 Q14. */
    val maxDocumentBytes: Int = 8 * 1024 * 1024,
    /** RFC 0004 Q2; the compiler enforces it. */
    val principalBindingLimit: Int = PolicyDocumentCompiler.DEFAULT_PRINCIPAL_BINDING_LIMIT,
    /** How long a change may stay unpublished before it expires (`cosec.control.changes.expire-after`). */
    val expireAfter: java.time.Duration = java.time.Duration.ofDays(7),
    /** How long an approval is valid (`cosec.control.approval.expire-after`, RFC 0007 §7.5). */
    val approvalExpireAfter: java.time.Duration = java.time.Duration.ofHours(72),
    /**
     * `cosec.control.approval.four-eyes` (RFC 0007 Q10): the trust root's own changes are analyzed too, and what the
     * analysis cannot prove needs another administrator.
     */
    val fourEyes: Boolean = false,
    /** When a shadow window may close (`cosec.control.shadow.*`, RFC 0007 §6.4). */
    val shadow: ShadowOptions = ShadowOptions(),
    /**
     * The longest a break-glass binding may live from the moment it publishes (`cosec.control.break-glass.max-duration`,
     * RFC 0007 §8): 4 hours by default, never more than 24.
     */
    val breakGlassMaxDuration: java.time.Duration = java.time.Duration.ofHours(4),
) {
    init {
        require(
            maxDocumentBytes > 0 && principalBindingLimit >= 0 && !expireAfter.isNegative && !approvalExpireAfter.isNegative &&
                breakGlassMaxDuration > java.time.Duration.ZERO && breakGlassMaxDuration <= MAX_BREAK_GLASS
        ) { "Invalid change options." }
    }
}

/** RFC 0007 §8: a break-glass binding never lives longer than this, whatever is configured. */
val MAX_BREAK_GLASS: java.time.Duration = java.time.Duration.ofHours(24)

/**
 * When a shadow window closes (RFC 0007 §6.4, Q1): once [minDuration] has passed and the instances have reported
 * [minSamples] samples that compared (not `BASE_MISMATCH`); or at [maxDuration] with fewer, as insufficient evidence
 * the publisher must acknowledge; or at once when an instance cannot compile or evaluate the candidate.
 */
class ShadowOptions(
    val minDuration: java.time.Duration = java.time.Duration.ofMinutes(15),
    val minSamples: Long = 1_000,
    val maxDuration: java.time.Duration = java.time.Duration.ofHours(24),
) {
    init {
        require(
            !minDuration.isNegative && minSamples >= 0 && maxDuration >= minDuration
        ) { "Invalid shadow window options." }
    }
}

/** What [ChangeChecks.check] found, and the heads of the other documents it compiled the candidate with. */
class CheckResult(val findings: List<Finding>, val checkedWith: Map<SnapshotKey, SnapshotDigest?>) {
    val clean: Boolean get() = findings.none { it.severity == Severity.ERROR }
}

/**
 * The checks of a candidate (RFC 0007 §6.2, §6.3), in order: V1 format, V2 size and names, then the strict compile
 * with the data plane's compiler ([PolicyDocumentCompiler]), against the current heads it combines with.
 *
 * - V1: the candidate must read back with the document codec; what fails is a 400 at its JSON path.
 * - V2: at most [ChangeOptions.maxDocumentBytes] encoded; the ids a change puts must not use the reserved prefixes
 *   `cosec:`, `g:` and `r:` (`perm:` is the app document's, which the compiler checks), unless it migrates or
 *   bootstraps; a tenant policy must not reuse a platform policy's id (RFC 0007 §3.1 N2).
 * - V3, compile: every entry must compile, with the limits and external attributes of the platform document
 *   (RFC 0007 §3.1 N3): a `Broken` entry is a 422, never published. An app or tenant candidate's bindings must
 *   resolve, in the candidate, the platform, or for a tenant binding of one app, that app's document (N1). A platform
 *   candidate must not remove a policy other documents refer to (§5.8).
 * - V4 has nothing to check: catalogs declare permission ids, not services, verbs or types (RFC 0004 §4.9). V5's
 *   break-glass rules and the `nomatch` confirmation (ATR-6) are the compiler's; a candidate's boundary must be one the
 *   platform defines (below); protected entries and grants are [ChangeAuthority]'s.
 *
 * Changing the platform's limits or attributes does not recompile the other documents; it is a warning.
 */
class ChangeChecks(
    private val options: ChangeOptions = ChangeOptions(),
    /** The host's `AttributeProvider` catalog: a candidate compiles against it as an instance would ([PolicyDocumentCompiler.forPlatform]). */
    private val attributes: AttributeCatalog = AttributeCatalog.NONE,
) {
    /** Reads what the checks combine a candidate with: current heads, and the back-references of the platform's policies. */
    interface Context {
        suspend fun head(key: SnapshotKey): Head?

        suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<SnapshotKey>
    }

    suspend fun check(
        key: SnapshotKey,
        candidate: PolicyDocument,
        kind: ProvenanceKind,
        operations: List<Operation>,
        base: PolicyDocument?,
        context: Context,
    ): CheckResult {
        val findings = ArrayList<Finding>()
        val bytes = PolicyDocumentCodec.encode(candidate)
        try {
            PolicyDocumentCodec.decode(candidate.kind, candidate.owner, bytes)
        } catch (error: SnapshotFormatException) {
            findings += Finding("V1", "$", error.message.orEmpty(), BAD_REQUEST)
            return CheckResult(findings, emptyMap())
        }
        if (bytes.size > options.maxDocumentBytes) {
            findings += Finding("V2", "$", "the document is ${bytes.size} bytes, more than ${options.maxDocumentBytes}", BAD_REQUEST)
        }
        reservedNames(operations, kind, findings)
        val checkedWith = LinkedHashMap<SnapshotKey, SnapshotDigest?>()
        val platform = if (key == SnapshotKey.Platform) {
            candidate
        } else {
            val head = context.head(SnapshotKey.Platform)
            checkedWith[SnapshotKey.Platform] = head?.digest
            head?.document
        }
        if (candidate.kind == DocumentKind.TENANT && platform != null) {
            val shared = platform.policies.map { it.id.value }.toSet()
            candidate.policies.withIndex().filter { it.value.id.value in shared }.forEach { (i, policy) ->
                findings += Finding("V2", "$.policies[$i].id", "[${policy.id.value}] is a platform policy's id", UNPROCESSABLE)
            }
        }
        try {
            PolicyDocumentCompiler.forPlatform(platform, attributes, options.principalBindingLimit).compile(candidate)
        } catch (error: DocumentCompileException) {
            error.errors.forEach { findings += Finding("COMPILE", it.path, it.message, UNPROCESSABLE) }
        }
        when (candidate.kind) {
            DocumentKind.PLATFORM -> {
                removedReferences(base, candidate, context, findings)
                sectionWarnings(base, candidate, findings)
            }

            else -> unresolved(candidate, platform, context, checkedWith, findings)
        }
        return CheckResult(findings, checkedWith)
    }

    /**
     * No change uses a reserved prefix: cosec-migrate writes its reserved ids through Revisions, never through a change,
     * so a change's kind exempts nothing (review K1).
     */
    @Suppress("UnusedParameter")
    private fun reservedNames(operations: List<Operation>, kind: ProvenanceKind, findings: MutableList<Finding>) {
        for ((i, operation) in operations.withIndex()) {
            val id = when (operation) {
                is Operation.PutPolicy -> operation.policy.id.value
                is Operation.PutBinding -> operation.binding.id.value
                else -> continue
            }
            RESERVED.firstOrNull { id.startsWith(it) }?.let { prefix ->
                findings += Finding("V2", "operations[$i]", "[$id]: the prefix [$prefix] is reserved for built-in policies and cosec-migrate", UNPROCESSABLE)
            }
        }
    }

    /**
     * Bindings of an app or tenant candidate whose policy is defined neither by it nor by the platform, nor, for a
     * tenant binding of exactly one app, by that app's document (RFC 0007 §3.1 N1).
     */
    private suspend fun unresolved(
        candidate: PolicyDocument,
        platform: PolicyDocument?,
        context: Context,
        checkedWith: MutableMap<SnapshotKey, SnapshotDigest?>,
        findings: MutableList<Finding>,
    ) {
        val defined = candidate.policies.map {
            it.id.value
        }.toSet() + platform?.policies?.map { it.id.value }.orEmpty() + BUILTIN
        val apps = HashMap<String, Set<String>>()
        for ((i, binding) in candidate.bindings.withIndex()) {
            val policy = binding.policy.value
            if (policy in defined) continue
            val app = (binding.scope.app as? Match.Exactly)?.value?.value
            val inApp = candidate.kind == DocumentKind.TENANT && app != null && policy in apps.getOrPut(app) {
                val head = context.head(SnapshotKey.App(app))
                checkedWith[SnapshotKey.App(app)] = head?.digest
                head?.document?.policies?.map { it.id.value }?.toSet().orEmpty()
            }
            if (!inApp) {
                findings += Finding("REFERENCES", "$.bindings[$i].policy", "[$policy] is defined neither here nor where this document may refer", UNPROCESSABLE)
            }
        }
        val boundary = candidate.boundary ?: return
        if (platform?.policies?.none { it.id == boundary && it.boundary } != false) {
            findings += Finding("REFERENCES", "$.boundary", "[${boundary.value}] is not a boundary of the platform", UNPROCESSABLE)
        }
    }

    private suspend fun removedReferences(
        base: PolicyDocument?,
        candidate: PolicyDocument,
        context: Context,
        findings: MutableList<Finding>,
    ) {
        val kept = candidate.policies.map { it.id }.toSet()
        for (policy in base?.policies.orEmpty().map { it.id }.filter { it !in kept }) {
            val referrers = context.referrers(SnapshotKey.Platform, policy)
            if (referrers.isNotEmpty()) {
                findings += Finding("REFERENCES", "$.policies", "[${policy.value}] is referred to by ${referrers.sorted()}", UNPROCESSABLE)
            }
        }
    }

    private fun sectionWarnings(base: PolicyDocument?, candidate: PolicyDocument, findings: MutableList<Finding>) {
        val limitsChanged = base?.limits?.let(::limitsOf) != candidate.limits?.let(::limitsOf)
        val attributesChanged = base?.attributes?.map { it.path to it.type } != candidate.attributes?.map { it.path to it.type }
        if (limitsChanged || attributesChanged) {
            findings += Finding(
                "V3",
                if (limitsChanged) "$.limits" else "$.attributes",
                "the other documents are not compiled again: one that no longer compiles fails closed on every instance",
                UNPROCESSABLE,
                Severity.WARNING,
            )
        }
    }

    private fun limitsOf(limits: me.ahoo.cosec.model.condition.ConditionLimits) =
        listOf(
            limits.maxNodes,
            limits.maxDepth,
            limits.maxInValues,
            limits.maxCidrBlocks,
            limits.maxLiteralBytes,
            limits.maxCost
        )

    private fun Set<SnapshotKey>.sorted(): List<String> = map { it.toString() }.sorted()

    internal companion object {
        private const val BAD_REQUEST = 400
        private const val UNPROCESSABLE = 422
        private val BUILTIN = BuiltinPolicies.CONTROL.keys.map { it.value } + BuiltinPolicies.ROOT.value
        val RESERVED = listOf("cosec:", "g:", "r:")
    }
}
