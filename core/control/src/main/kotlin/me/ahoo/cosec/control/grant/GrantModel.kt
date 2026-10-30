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

import me.ahoo.cosec.engine.flat.OnMissing
import me.ahoo.cosec.engine.flat.SubjectAttributes
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Condition

/**
 * A statement as the grant analysis reads it (RFC 0005 §5.1): its action and resource patterns and its condition as
 * written. The analysis works on the written condition because it substitutes the grantor's attributes into it before
 * checking it again (RFC 0007 §7.4.3).
 */
class GrantStatement(
    val effect: Effect,
    val actions: List<ActionPattern>,
    /** A statement without a resource matches every resource: [ResourcePattern.AnyResource]. */
    val resources: List<ResourcePattern> = listOf(ResourcePattern.AnyResource),
    val condition: Condition? = null,
    val name: String = "",
    /** What an external attribute its condition reads that cannot be fetched means; `null` for the default. */
    val onMissing: OnMissing? = null,
) {
    init {
        require(actions.isNotEmpty()) { "A statement names at least one action." }
        require(resources.isNotEmpty()) { "A statement names at least one resource; AnyResource for every resource." }
    }
}

/** A policy: its statements, its condition (which every statement also needs) and its entry flags. */
class GrantPolicy(
    val id: PolicyId,
    val statements: List<GrantStatement>,
    val condition: Condition? = null,
    /** Whether a principal may attach it (RFC 0004 §4.4). */
    val attachable: Boolean = false,
    /** Changing or removing it needs `cosec:unprotect` (RFC 0007 §7.4.4, Q9). */
    val protected: Boolean = false,
)

/** The policies and bindings of one snapshot document, as the analysis reads them. */
class GrantDocument(
    val policies: List<GrantPolicy> = emptyList(),
    val bindings: List<Binding> = emptyList(),
    /** The bindings whose change or removal needs `cosec:unprotect`. */
    val protectedBindings: Set<BindingId> = emptySet(),
) {
    private val byId: Map<PolicyId, GrantPolicy> = policies.associateBy { it.id }

    fun policy(id: PolicyId): GrantPolicy? = byId[id]

    companion object {
        val EMPTY: GrantDocument = GrantDocument()
    }
}

/**
 * The published snapshot the grantor's own rights are read from (RFC 0007 §7.4.2): the documents at [now]. [apps]
 * holds every app document, whether or not a change names its app (RC1-4): an app document binds roles, so the grantor
 * may hold or be denied something in any app.
 */
class GrantSnapshot(
    val platform: GrantDocument = GrantDocument.EMPTY,
    val apps: Map<AppId, GrantDocument> = emptyMap(),
    val tenants: Map<TenantId, GrantDocument> = emptyMap(),
    /** Epoch milliseconds: bindings that expired by then do not apply. */
    val now: Long,
    /**
     * The apps whose document could not be read, so what the grantor holds or is denied there is not known (RC1-4). By
     * default [Dimension.Every], every app [apps] does not have: a snapshot that does not say it holds every app document
     * might not. [Dimension.Only] names the apps that failed when [apps] holds every other one. A fragment that reaches
     * one of them is never contained.
     */
    val unreadApps: Dimension<AppId> = Dimension.Every(),
) {
    fun tenant(id: TenantId): GrantDocument = tenants[id] ?: GrantDocument.EMPTY
}

/**
 * The principal of a control-plane request, with the memberships of that request (RFC 0007 §7.4.2): always
 * authenticated. [attributes] are its token's `attributes` claim, of every JSON type, as the engine holds them; the
 * analysis reads `subject.attributes.<name>` from them through the engine's own [SubjectAttributes.string] (RC1-2).
 */
class Grantor(
    val tenant: TenantId,
    val id: PrincipalId,
    val memberships: Memberships,
    val attributes: Map<String, Any>,
    /**
     * Whether what is bound to the grantor by name (attachments, principal bindings) grants it anything here: false for
     * a credential with a role cap (RFC 0009 §4.2), which does not hold those ALLOWs, and so cannot grant them either.
     * Their DENYs bind it all the same (RC1-1).
     */
    val principalBindings: Boolean,
)

/** A change to the document of [tenant]: [candidate] replaces the document the snapshot has (RFC 0007 §7.4.1). */
class GrantChange(val tenant: TenantId, val candidate: GrantDocument)
