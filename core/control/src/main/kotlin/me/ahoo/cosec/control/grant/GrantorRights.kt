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

import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef

/**
 * A statement the grantor is subject to, with where it applies to the grantor ([applicability]) and its condition
 * together with its policy's ([condition], `null` when neither has one).
 */
class HeldStatement(
    val statement: GrantStatement,
    val condition: Condition?,
    val applicability: ScopeSet,
    val ref: StatementRef.Policy,
    /** Reached through an `Anyone` binding: it binds every grantee just as it binds the grantor (Q8). */
    val anyone: Boolean,
    /**
     * When the binding it is held through expires (epoch milliseconds), `null` for one that does not, or for an
     * attachment. The grantor holds it only until then, so it contains only grants that end no later (RC1-3).
     */
    val expiresAt: Long? = null,
)

/**
 * The grantor's own statements in the published snapshot (RFC 0007 §7.4.3 steps 2 and 3): its applicable ALLOWs, and
 * the DENYs that bind it other than through `Anyone`.
 *
 * - `Anyone` and principal bindings apply in the binding's `(app, space)`; attachments in `(*, *)`; a role binding
 *   `(app, space)` in `app` and those of its spaces where the grantor holds the role.
 * - A capped grantor (no principal bindings, RFC 0009 §4.2) holds none of the ALLOWs of its attachments and principal
 *   bindings, and is subject to all of their DENYs, as on the data plane (RC1-1): a cap removes ALLOWs only.
 * - An app document is loaded only for requests in its app, so its bindings apply only there.
 * - A binding or attachment whose policy does not resolve makes the data plane answer `Indeterminate` wherever it
 *   applies, so the grantor is not permitted there: it counts as a DENY of every action and resource. Through
 *   `Anyone` it fails every grantee alike and does not count. A tenant binding or an attachment of an id both the
 *   tenant and the platform define counts the same way: the engine refuses it as ambiguous unless both have the same
 *   content (RFC 0007 N2, RC1-9), and the analysis, which does not compare compiled content, takes every such id as
 *   ambiguous, which only takes rights away. Expired bindings, the blacklist and the break-glass root are left out: the blacklist
 *   binds every grantee too, and leaving out an ALLOW only makes the analysis stricter.
 * - A statement held through a binding with `expiresAt` carries that expiry ([HeldStatement.expiresAt], RC1-3).
 */
class GrantorRights(val allows: List<HeldStatement>, val denies: List<HeldStatement>) {
    companion object {
        private val BUILTIN = setOf(PolicyId("cosec:blacklist"), PolicyId("cosec:root"))
        private val EVERYTHING = GrantStatement(Effect.DENY, listOf(ActionPattern.ANY), name = "unresolved")

        fun of(grantor: Grantor, snapshot: GrantSnapshot): GrantorRights {
            val collector = Collector(grantor, snapshot)
            collector.document(snapshot.platform, DocumentKind.PLATFORM, null)
            snapshot.apps.forEach { (app, document) -> collector.document(document, DocumentKind.APP, app) }
            collector.document(snapshot.tenant(grantor.tenant), DocumentKind.TENANT, null)
            collector.attachments()
            return GrantorRights(collector.allows, collector.denies)
        }
    }

    private class Collector(private val grantor: Grantor, private val snapshot: GrantSnapshot) {
        val allows = ArrayList<HeldStatement>()
        val denies = ArrayList<HeldStatement>()

        fun document(document: GrantDocument, kind: DocumentKind, app: AppId?) {
            for (binding in document.bindings) {
                if (binding.expiresAt?.let { snapshot.now >= it } == true) {
                    continue
                }
                var applicability = applicability(binding) ?: continue
                if (app != null) {
                    applicability = applicability.intersect(ScopeSet(Dimension.Only(setOf(app)), Dimension.Every()))
                }
                if (applicability.isEmpty || binding.policy in BUILTIN) {
                    continue
                }
                val via = BindingRef.Bound(binding.id, kind)
                val anyone = binding.subject == SubjectRef.Anyone
                val policy = resolve(document, kind, binding)
                hold(
                    policy,
                    binding.policy,
                    applicability,
                    via,
                    anyone,
                    byName = binding.subject is SubjectRef.Principal,
                    expiresAt = binding.expiresAt,
                )
            }
        }

        fun attachments() {
            for (id in grantor.memberships.attachments) {
                val own = snapshot.tenant(grantor.tenant).policy(id)
                val shared = snapshot.platform.policy(id)
                val policy = if (own != null && shared != null) null else (own ?: shared)?.takeIf { it.attachable }
                hold(policy, id, ScopeSet.EVERY, BindingRef.Attached(id), anyone = false, byName = true)
            }
        }

        /** [byName]: bound to the grantor by name, so a capped grantor holds only its DENYs. */
        private fun hold(
            policy: GrantPolicy?,
            id: PolicyId,
            applicability: ScopeSet,
            via: BindingRef,
            anyone: Boolean,
            byName: Boolean,
            expiresAt: Long? = null,
        ) {
            val grants = !byName || grantor.principalBindings
            if (policy == null) {
                if (!anyone) {
                    denies += HeldStatement(EVERYTHING, null, applicability, StatementRef.Policy(id, -1, "", via), false)
                }
                return
            }
            for ((index, statement) in policy.statements.withIndex()) {
                val held = HeldStatement(
                    statement = statement,
                    condition = both(policy.condition, statement.condition),
                    applicability = applicability,
                    ref = StatementRef.Policy(policy.id, index, statement.name, via),
                    anyone = anyone,
                    expiresAt = expiresAt,
                )
                when {
                    statement.effect == Effect.ALLOW -> if (grants) allows += held
                    !anyone -> denies += held
                }
            }
        }

        /** Where [binding] applies to the grantor, or `null` if its subject is not the grantor. */
        private fun applicability(binding: Binding): ScopeSet? {
            val scope = ScopeSet.of(binding.scope.app, binding.scope.space)
            return when (val subject = binding.subject) {
                SubjectRef.Anyone -> scope
                is SubjectRef.Principal -> scope.takeIf { subject.tenant == grantor.tenant && subject.id == grantor.id }

                is SubjectRef.Role -> {
                    if (subject.tenant != null && subject.tenant != grantor.tenant) {
                        return null
                    }
                    val spaces = grantor.memberships.roles.filterValues { subject.id in it }.keys
                    scope.intersect(ScopeSet(Dimension.Every(), Dimension.Only(spaces)))
                }
            }
        }

        /**
         * A binding's policy: in its own document, else in the platform document; a tenant binding of an app may also
         * point to that app's permission policies (RFC 0007 §3.1 N1). A tenant's id the platform also defines is
         * ambiguous: `null`.
         */
        private fun resolve(document: GrantDocument, kind: DocumentKind, binding: Binding): GrantPolicy? {
            val own = document.policy(binding.policy)
            val shared = if (kind != DocumentKind.PLATFORM) snapshot.platform.policy(binding.policy) else null
            if (own != null) {
                return if (kind == DocumentKind.TENANT && shared != null) null else own
            }
            shared?.let { return it }
            val app = binding.scope.app
            if (kind == DocumentKind.TENANT && app is Match.Exactly) {
                return snapshot.apps[app.value]?.policy(binding.policy)
            }
            return null
        }
    }
}

/** `and` of two optional conditions. */
internal fun both(a: Condition?, b: Condition?): Condition? = when {
    a == null -> b
    b == null -> a
    else -> Condition.And(listOf(a, b))
}

/** The roles of the grantor in [space]. */
internal fun Grantor.roles(space: SpaceId): Set<String> =
    memberships.roles[space].orEmpty().mapTo(LinkedHashSet()) { it.value }
