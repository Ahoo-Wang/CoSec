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

import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.PolicyDocument

/**
 * The grant analysis's view of snapshot documents (RFC 0004 §7.1): what [GrantAnalyzer] reads from the documents the
 * control plane publishes.
 *
 * - The platform document has the built-in control-plane policies (RFC 0007 §7.3), as the engine's platform does.
 * - Break-glass bindings are left out: they let their principal act in an emergency, never grant (a check that only
 *   break-glass permits is refused as well, RFC 0006 §9.4.3). Leaving out a grantor's binding only takes rights away.
 * - The document format has no `protected` flag yet; no entry is protected until it does (RFC 0007 §7.4.4).
 */
object GrantDocuments {
    fun of(document: PolicyDocument?): GrantDocument {
        if (document == null) {
            return GrantDocument.EMPTY
        }
        return GrantDocument(
            policies = document.policies.map(::policy),
            bindings = document.bindings.filterNot { it.breakGlass },
        )
    }

    /** The platform document, with the built-in control-plane policies. */
    fun platform(document: PolicyDocument?): GrantDocument {
        val own = of(document)
        val builtin = BuiltinPolicies.CONTROL.map { (id, statements) -> GrantPolicy(id, statements.map(::statement)) }
        return GrantDocument(own.policies + builtin, own.bindings)
    }

    fun snapshot(
        platform: PolicyDocument?,
        apps: Map<AppId, PolicyDocument?>,
        tenants: Map<TenantId, PolicyDocument?>,
        now: Long,
        unreadApps: Dimension<AppId> = Dimension.Only(emptySet()),
    ): GrantSnapshot = GrantSnapshot(
        platform = platform(platform),
        apps = apps.mapValues { of(it.value) },
        tenants = tenants.mapValues { of(it.value) },
        now = now,
        unreadApps = unreadApps,
    )

    private fun policy(definition: PolicyDefinition): GrantPolicy = GrantPolicy(
        id = definition.id,
        statements = definition.statements.map(::statement),
        condition = definition.condition,
        attachable = definition.attachable,
    )

    private fun statement(source: StatementSource): GrantStatement = GrantStatement(
        effect = source.effect,
        actions = source.actions.map(ActionPattern::parse),
        resources = source.resources.map {
            ResourcePattern.parse(
                it.pattern,
                it.caseSensitive,
                it.decodeAndParseSegments
            )
        }
            .ifEmpty { listOf(ResourcePattern.AnyResource) },
        condition = source.condition,
        name = source.name,
    )
}
