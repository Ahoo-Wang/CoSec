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

import me.ahoo.cosec.engine.flat.OnMissing
import me.ahoo.cosec.engine.flat.OnMissingConfirmation
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.document.ProvenanceKind
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Duration
import java.time.Instant

/**
 * A confirmation an author gives with a change (RFC 0007 §9.5): that statement [statement] of [policy], whose content
 * has [digest] ([StatementDigest]), may do what [kind] says. Only `deny-nomatch` exists: a DENY with
 * `onMissing: nomatch`, which lapses while its attributes cannot be fetched.
 */
data class Confirmation(val policy: PolicyId, val statement: Int, val digest: String, val kind: String = DENY_NOMATCH) {
    init {
        require(statement >= 0 && kind == DENY_NOMATCH) { "A confirmation is deny-nomatch, of a statement index." }
    }

    companion object {
        const val DENY_NOMATCH: String = "deny-nomatch"
    }
}

/**
 * The digest of a statement's content as the document format writes it, without any confirmation it carries, together
 * with the condition of its policy, which guards it as much as its own: what a [Confirmation] names. A statement, or a
 * policy condition, that changes no longer matches the confirmation given for it (review K4).
 */
object StatementDigest {
    fun of(statement: StatementSource, policyCondition: Condition? = null): String {
        val bare = PolicyDefinition(PolicyId("statement"), false, policyCondition, listOf(unconfirmed(statement)))
        val tree = PolicyDocumentCodec.policyTree(bare)
        val statementTree = tree.get("statements").get(0)
        // Without a policy condition, the statement's own digest, as before: confirmations given for one still match.
        val policyTree = tree.get("condition") ?: return EntryDigest.sha256(statementTree)
        val content = JsonNodeFactory.instance.objectNode()
        content.set("statement", statementTree)
        content.set("policyCondition", policyTree)
        return EntryDigest.sha256(content)
    }

    internal fun unconfirmed(statement: StatementSource): StatementSource =
        if (statement.onMissingConfirmed == null) {
            statement
        } else {
            StatementSource(
                statement.name,
                statement.effect,
                statement.actions,
                statement.resources,
                statement.condition,
                statement.onMissing
            )
        }
}

/**
 * The control plane's side of `nomatch` DENYs (RFC 0007 §9.5, N6): it alone writes `onMissingConfirmed`, and only for a
 * statement the change's author confirmed, or one the base already had confirmed with the same content. Any other
 * confirmation in a candidate is dropped, so an author cannot copy one; a DENY with `nomatch` left without one is a
 * 428 (and would not compile).
 */
internal object NomatchConfirmations {
    fun apply(
        candidate: PolicyDocument,
        base: PolicyDocument?,
        confirmations: List<Confirmation>,
        author: Actor,
        change: ChangeId,
    ): Pair<PolicyDocument, List<Finding>> {
        val findings = ArrayList<Finding>()
        val before = base?.policies.orEmpty().associateBy { it.id }
        val policies = candidate.policies.mapIndexed { p, policy ->
            val statements = policy.statements.mapIndexed { s, statement ->
                val bare = StatementDigest.unconfirmed(statement)
                if (statement.effect != Effect.DENY || statement.onMissing != OnMissing.NOMATCH) {
                    return@mapIndexed bare
                }
                val digest = StatementDigest.of(statement, policy.condition)
                val previous = before[policy.id]
                val kept = previous?.statements?.getOrNull(s)
                    ?.takeIf { it.onMissingConfirmed != null && StatementDigest.of(it, previous.condition) == digest }
                    ?.onMissingConfirmed
                val confirmed = confirmations.any { it.policy == policy.id && it.statement == s && it.digest == digest }
                when {
                    kept != null -> confirmedAs(bare, kept)
                    confirmed -> confirmedAs(
                        bare,
                        OnMissingConfirmation(author.tenant.value, author.principal.value, change.value)
                    )
                    else -> {
                        findings += Finding(
                            "V5",
                            "$.policies[$p].statements[$s]",
                            "a DENY with onMissing: nomatch needs a deny-nomatch confirmation of digest $digest",
                            PRECONDITION_REQUIRED,
                        )
                        bare
                    }
                }
            }
            PolicyDefinition(policy.id, policy.attachable, policy.condition, statements, boundary = policy.boundary)
        }
        return PolicyDocument(
            kind = candidate.kind,
            owner = candidate.owner,
            revision = candidate.revision,
            publishedAt = candidate.publishedAt,
            policies = policies,
            bindings = candidate.bindings,
            catalog = candidate.catalog,
            boundary = candidate.boundary,
            provenance = candidate.provenance,
            limits = candidate.limits,
            attributes = candidate.attributes,
            rateLimits = candidate.rateLimits,
        ) to findings
    }

    private fun confirmedAs(statement: StatementSource, confirmation: OnMissingConfirmation) = StatementSource(
        statement.name,
        statement.effect,
        statement.actions,
        statement.resources,
        statement.condition,
        statement.onMissing,
        confirmation,
    )

    private const val PRECONDITION_REQUIRED = 428
}

/**
 * Break-glass (RFC 0007 §8): bindings created only by a change of kind `BREAK_GLASS`, which carries nothing else, with
 * an expiry no further than [maxDuration] from [now] (checked again when it publishes). What a live break-glass binding
 * grants is its policy's statements, so that policy does not change while the binding lives (round-2 review R4):
 * otherwise an ordinary change, or a rollback, would widen an approved emergency grant without a second person.
 */
internal object BreakGlassRules {
    fun findings(
        kind: ProvenanceKind,
        operations: List<Operation>,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        now: Instant,
        maxDuration: Duration,
    ): List<Finding> {
        val findings = ArrayList<Finding>()
        val before = base?.bindings.orEmpty().associateBy { it.id }
        val request = kind == ProvenanceKind.BREAK_GLASS
        // No kind is exempt: MIGRATE and BOOTSTRAP revisions are written by cosec-migrate through Revisions, never
        // through a change, so a change claiming either is held to every rule (review K1).
        for ((i, binding) in candidate.bindings.withIndex()) {
            val previous = before[binding.id]
            val changed = previous == null ||
                EntryDigest.of(previous, candidate.kind, candidate.owner) != EntryDigest.of(binding, candidate.kind, candidate.owner)
            if (changed) {
                findings += binding(i, binding, previous, request, now, maxDuration)
            }
        }
        if (request) {
            findings += onlyBreakGlass(operations, before.values.filter { it.breakGlass }.map { it.id }.toSet())
        }
        findings += grantedPolicies(base, candidate, now)
        return findings
    }

    /** The policies of [candidate] a break-glass binding live at [now] grants, changed from [base]. */
    private fun grantedPolicies(base: PolicyDocument?, candidate: PolicyDocument, now: Instant): List<Finding> {
        val granted = live(candidate, now).map { it.policy }.toSet()
        val before = base?.policies.orEmpty().associateBy { it.id }
        return candidate.policies.withIndex().filter { (_, policy) ->
            policy.id in granted && before[policy.id]?.let(EntryDigest::of) != EntryDigest.of(policy)
        }.map { (i, policy) ->
            Finding(
                "V5",
                "$.policies[$i]",
                "[${policy.id.value}] is granted by a live break-glass binding: revoke it before changing the policy",
                UNPROCESSABLE
            )
        }
    }

    /** The break-glass bindings of [document] that have not expired at [now]: what still grants. */
    private fun live(document: PolicyDocument?, now: Instant): List<me.ahoo.cosec.model.Binding> =
        document?.bindings.orEmpty().filter { binding ->
            binding.breakGlass && binding.expiresAt?.let { it > now.toEpochMilli() } ?: true
        }

    /** What is wrong with changed binding [i], [binding], that was [previous], in a change that is a [request] or not. */
    private fun binding(
        i: Int,
        binding: me.ahoo.cosec.model.Binding,
        previous: me.ahoo.cosec.model.Binding?,
        request: Boolean,
        now: Instant,
        maxDuration: Duration,
    ): List<Finding> {
        val findings = ArrayList<Finding>()
        if (previous?.breakGlass == true && !binding.breakGlass && !request) {
            findings += Finding("V5", "$.bindings[$i]", "a break-glass binding is changed only by a break-glass request", UNPROCESSABLE)
        }
        if (!binding.breakGlass) {
            return findings
        }
        if (!request) {
            findings += Finding("V5", "$.bindings[$i]", "a break-glass binding is created only by a break-glass request", UNPROCESSABLE)
        }
        val expiresAt = binding.expiresAt?.let(Instant::ofEpochMilli)
        if (expiresAt == null || !expiresAt.isAfter(now) || Duration.between(now, expiresAt) > maxDuration) {
            findings += Finding(
                "V5",
                "$.bindings[$i].expiresAt",
                "a break-glass binding expires after now and within $maxDuration of it, not at $expiresAt",
                UNPROCESSABLE,
            )
        }
        return findings
    }

    /**
     * Whether [operations] create or alter a break-glass binding of [base]: one that puts a break-glass binding, puts
     * another binding over one, or puts the policy a binding live at [now] grants (round-2 review R4). Every
     * break-glass rule keys on this, never on the change's kind alone (review K1): `cosec:breakglass`, the REQUIRED
     * audit and its `BREAK_GLASS_REQUESTED` event. A rollback is no exception. Removing one only narrows.
     */
    fun touches(operations: List<Operation>, base: PolicyDocument?, now: Instant): Boolean {
        val glass = base?.bindings.orEmpty().filter { it.breakGlass }.map { it.id }.toSet()
        val granted = live(base, now).map { it.policy }.toSet()
        return operations.any {
            (it is Operation.PutBinding && (it.binding.breakGlass || it.binding.id in glass)) ||
                (it is Operation.PutPolicy && it.policy.id in granted)
        }
    }

    /** The operations of a break-glass change that neither create nor revoke a break-glass binding. */
    private fun onlyBreakGlass(operations: List<Operation>, glass: Set<me.ahoo.cosec.model.BindingId>): List<Finding> =
        operations.withIndex().filterNot { (_, op) ->
            (op is Operation.PutBinding && op.binding.breakGlass) || (op is Operation.RemoveBinding && op.id in glass)
        }.map { (i, _) ->
            Finding(
                "V5",
                "operations[$i]",
                "a break-glass change creates or revokes break-glass bindings only",
                UNPROCESSABLE
            )
        }

    private const val UNPROCESSABLE = 422
}
