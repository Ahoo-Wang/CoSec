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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.engine.StatementSource
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.condition.Operand
import me.ahoo.cosec.model.decision.DocumentKind

/**
 * What in the published documents refuses a principal by its id (DR2-M-1, `MIG-6`): a key that moves to a principal of
 * its own (`apikey:<id>`) escapes all of it, so `api-keys` keeps such a key on its principal. Read as the engine reads
 * what a document grants ([RoleExpansion.effective]), over the documents that can decide a request of the principal's
 * tenant: the platform's, every app's, and that tenant's own.
 *
 * - A condition reading `subject.id` in the DENY direction (a DENY statement or its policy's condition, or a negation in
 *   an ALLOW, each `not` turning the direction): it names the principal when it compares it with a literal that holds
 *   of it (`eq`, `in`, `prefix`), and is undecided when it compares `subject.id` with anything else (another
 *   attribute), which names every principal: what cannot be said exactly is refused.
 * - A principal binding naming the principal whose policy holds a DENY statement; one whose policy no document defines
 *   names it too (it fails closed now, and might not later).
 * - A policy the membership provider attaches to the principal (`--memberships`' `attachments`) that holds a DENY.
 *
 * A boundary only narrows, and is not read.
 */
internal object PrincipalDenies {
    private val SUBJECT_ID = Attribute.Kind.SUBJECT_ID.path

    /** The DENYs of [documents] that name [principal] of [tenant], each as where it is; empty for none. */
    fun of(
        documents: List<PolicyDocument>,
        tenant: TenantId,
        principal: String,
        attachments: Collection<String> = emptyList(),
    ): List<String> {
        val deciding = documents.filter { it.kind != DocumentKind.TENANT || it.owner == tenant.value }
            .map(RoleExpansion::effective)
        val platform = deciding.firstOrNull { it.kind == DocumentKind.PLATFORM }
        val named = ArrayList<String>()
        for (document in deciding) {
            for (policy in document.policies.filterNot { it.boundary }) {
                val prefix = "${where(document)} policy [${printable(policy.id)}]"
                statements(policy, principal).forEach { named += "$prefix $it" }
            }
        }
        val denied = bindings(documents, tenant, principal)
        denied.forEach { named += "the binding [${Identifiers.printable(it)}] of a DENY" }
        val tenantDocument = deciding.firstOrNull { it.kind == DocumentKind.TENANT }
        for (attached in attachments) {
            val policy = policyOf(PolicyId(attached), tenantDocument, platform)
            if (policy == null || policy.holdsDeny()) {
                named += "the attachment [${Identifiers.printable(attached)}] the membership provider gives it, a DENY"
            }
        }
        return named.distinct()
    }

    /**
     * The bindings of [documents] naming [principal] of [tenant] whose policy holds a DENY (or no document defines), as
     * `document/binding`.
     */
    fun bindings(documents: List<PolicyDocument>, tenant: TenantId, principal: String): List<String> =
        naming(documents, tenant, principal) { it?.holdsDeny() != false }

    /**
     * The bindings of [documents] naming [principal] of [tenant] whose policy holds an ALLOW (or no document defines),
     * break-glass bindings included, as `document/binding`.
     */
    fun grants(documents: List<PolicyDocument>, tenant: TenantId, principal: String): List<String> =
        naming(documents, tenant, principal, ::grantsAnything)

    private fun grantsAnything(policy: PolicyDefinition?): Boolean =
        policy?.statements?.any { it.effect == Effect.ALLOW } != false

    private fun naming(
        documents: List<PolicyDocument>,
        tenant: TenantId,
        principal: String,
        holds: (PolicyDefinition?) -> Boolean,
    ): List<String> {
        val effective = documents.filter { it.kind != DocumentKind.TENANT || it.owner == tenant.value }
            .map(RoleExpansion::effective)
        val platform = effective.firstOrNull { it.kind == DocumentKind.PLATFORM }
        return effective.flatMap { document ->
            document.bindings.filter { binding ->
                val subject = binding.subject as? SubjectRef.Principal
                subject != null && subject.tenant == tenant && subject.id.value == principal &&
                    holds(policyOf(binding.policy, document, platform))
            }.map { "${describe(document)}/${it.id.value}" }
        }
    }

    /** Whether [policy] holds a DENY statement. */
    private fun PolicyDefinition.holdsDeny(): Boolean = statements.any { it.effect == Effect.DENY }

    /** The statements of [policy] that name [principal], each as `statement <n>` and how. */
    private fun statements(policy: PolicyDefinition, principal: String): List<String> =
        policy.statements.flatMapIndexed { i, statement ->
            val deny = statement.effect == Effect.DENY
            (reads(policy.condition, deny, principal) + reads(statement.condition, deny, principal)).distinct()
                .map { "statement $i (${label(statement)}): $it" }
        }

    /**
     * How [condition], read in the DENY direction when [deny], names [principal]: `matches` for a literal that holds
     * of it, `undecided` for a comparison of `subject.id` with another value; nothing when it names no one.
     */
    private fun reads(condition: Condition?, deny: Boolean, principal: String): List<String> = when (condition) {
        null, is Condition.Time, is Condition.Cidr, is Condition.Exists, is Condition.Contains -> emptyList()
        is Condition.Not -> reads(condition.condition, !deny, principal)
        is Condition.And -> condition.conditions.flatMap { reads(it, deny, principal) }
        is Condition.Or -> condition.conditions.flatMap { reads(it, deny, principal) }
        is Condition.Eq -> if (deny) eq(condition, principal) else emptyList()
        is Condition.In -> if (deny && condition.attribute == SUBJECT_ID) {
            listOfNotNull(MATCHES.takeIf { condition.values.any { (it as? Literal.Str)?.value == principal } })
        } else {
            emptyList()
        }
        is Condition.Prefix -> if (deny && condition.attribute == SUBJECT_ID) {
            val prefix = (condition.value as? Literal.Str)?.value
            listOfNotNull(MATCHES.takeIf { prefix != null && principal.startsWith(prefix, condition.ignoreCase) })
        } else {
            emptyList()
        }
    }

    private fun eq(condition: Condition.Eq, principal: String): List<String> {
        val right = condition.value
        val readsId = condition.attribute == SUBJECT_ID || (right as? Operand.Attr)?.path == SUBJECT_ID
        return when {
            !readsId -> emptyList()
            condition.attribute == SUBJECT_ID && right is Literal.Str ->
                listOfNotNull(MATCHES.takeIf { right.value.equals(principal, condition.ignoreCase) })
            else -> listOf(UNDECIDED)
        }
    }

    private fun policyOf(id: PolicyId, document: PolicyDocument?, platform: PolicyDocument?): PolicyDefinition? =
        document?.policies?.firstOrNull { it.id == id } ?: platform?.policies?.firstOrNull { it.id == id }

    private fun where(document: PolicyDocument): String = "the ${describe(document)} document's"

    private fun describe(document: PolicyDocument): String =
        if (document.kind == DocumentKind.PLATFORM) "platform" else "${document.kind.name.lowercase()}:${document.owner}"

    private fun label(statement: StatementSource): String {
        val name = statement.name.takeIf { it.isNotEmpty() }?.let(Identifiers::printable)
        return if (name == null) statement.effect.name else "${statement.effect.name} $name"
    }

    private fun printable(id: PolicyId): String = Identifiers.printable(id.value)

    private const val MATCHES = "compares subject.id with a value that holds of this principal"
    private const val UNDECIDED = "compares subject.id with another value, which the documents do not settle"
}
