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
)

/**
 * The grantor's own statements in the published snapshot (RFC 0007 §7.4.3 steps 2 and 3): its applicable ALLOWs, and
 * the DENYs that bind it other than through `Anyone`.
 *
 * - `Anyone` and principal bindings apply in the binding's `(app, space)`; attachments in `(*, *)`; a role binding
 *   `(app, space)` in `app` and those of its spaces where the grantor holds the role.
 * - An app document is loaded only for requests in its app, so its bindings apply only there.
 * - A binding or attachment whose policy does not resolve makes the data plane answer `Indeterminate` wherever it
 *   applies, so the grantor is not permitted there: it counts as a DENY of every action and resource. Through
 *   `Anyone` it fails every grantee alike and does not count. Expired bindings, the blacklist and the break-glass root
 *   are left out: the blacklist binds every grantee too, and leaving out an ALLOW only makes the analysis stricter.
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
                hold(policy, binding.policy, applicability, via, anyone)
            }
        }

        fun attachments() {
            for (id in grantor.memberships.attachments) {
                val policy = (snapshot.tenant(grantor.tenant).policy(id) ?: snapshot.platform.policy(id))
                    ?.takeIf { it.attachable }
                hold(policy, id, ScopeSet.EVERY, BindingRef.Attached(id), anyone = false)
            }
        }

        private fun hold(policy: GrantPolicy?, id: PolicyId, applicability: ScopeSet, via: BindingRef, anyone: Boolean) {
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
                )
                when {
                    statement.effect == Effect.ALLOW -> allows += held
                    !anyone -> denies += held
                }
            }
        }

        /** Where [binding] applies to the grantor, or `null` if its subject is not the grantor. */
        private fun applicability(binding: Binding): ScopeSet? {
            val scope = ScopeSet.of(binding.scope.app, binding.scope.space)
            return when (val subject = binding.subject) {
                SubjectRef.Anyone -> scope
                // A capped credential holds nothing bound to its principal by name (RFC 0009 §4.2): it cannot lend it.
                is SubjectRef.Principal -> scope.takeIf {
                    grantor.principalBindings && subject.tenant == grantor.tenant && subject.id == grantor.id
                }

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
         * point to that app's permission policies (RFC 0007 §3.1 N1).
         */
        private fun resolve(document: GrantDocument, kind: DocumentKind, binding: Binding): GrantPolicy? {
            document.policy(binding.policy)?.let { return it }
            if (kind != DocumentKind.PLATFORM) {
                snapshot.platform.policy(binding.policy)?.let { return it }
            }
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
