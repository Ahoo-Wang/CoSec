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
import me.ahoo.cosec.snapshot.document.Actor
import me.ahoo.cosec.snapshot.document.PolicyDefinition
import me.ahoo.cosec.snapshot.document.PolicyDocument
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import me.ahoo.cosec.snapshot.document.ProvenanceKind
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
 * The digest of a statement's content as the document format writes it, without any confirmation it carries: what a
 * [Confirmation] names. A statement that changes no longer matches the confirmation given for it.
 */
object StatementDigest {
    fun of(statement: StatementSource): String {
        val bare = PolicyDefinition(PolicyId("statement"), false, null, listOf(unconfirmed(statement)))
        return EntryDigest.sha256(PolicyDocumentCodec.policyTree(bare).get("statements").get(0))
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
                val digest = StatementDigest.of(statement)
                val kept = before[policy.id]?.statements?.getOrNull(s)
                    ?.takeIf { it.onMissingConfirmed != null && StatementDigest.of(it) == digest }?.onMissingConfirmed
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
            PolicyDefinition(policy.id, policy.attachable, policy.condition, statements)
        }
        return PolicyDocument(
            candidate.kind,
            candidate.owner,
            candidate.revision,
            candidate.publishedAt,
            policies,
            candidate.bindings,
            candidate.catalog,
            candidate.provenance,
            candidate.limits,
            candidate.attributes,
            candidate.rateLimits,
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
 * an expiry no further than [maxDuration] from [now] (checked again when it publishes).
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
        val exempt = kind == ProvenanceKind.MIGRATE || kind == ProvenanceKind.BOOTSTRAP
        for ((i, binding) in candidate.bindings.withIndex()) {
            val previous = before[binding.id]
            val changed = previous == null || EntryDigest.of(previous, candidate.kind, candidate.owner) != EntryDigest.of(binding, candidate.kind, candidate.owner)
            if (!binding.breakGlass || !changed) continue
            if (kind != ProvenanceKind.BREAK_GLASS && !exempt) {
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
        }
        if (kind == ProvenanceKind.BREAK_GLASS) {
            findings += onlyBreakGlass(operations, before.values.filter { it.breakGlass }.map { it.id }.toSet())
        }
        return findings
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
