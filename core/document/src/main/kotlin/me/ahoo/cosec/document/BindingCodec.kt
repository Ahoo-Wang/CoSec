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

package me.ahoo.cosec.document

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.DocumentJson.factory
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Match.Companion.WILDCARD
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/** A binding, both ways, with its subject and its app and space matches. */
internal object BindingCodec {
    private val BINDING_FIELDS = arrayOf("id", "subject", "app", "space", "policy", "expiresAt", "breakGlass")

    internal fun decode(node: DocumentNode, kind: DocumentKind, owner: String): Binding {
        node.requireObject(*BINDING_FIELDS)
        val id = node.required("id").text()
        return try {
            Binding(
                id = BindingId(id),
                subject = subject(node.required("subject"), kind, owner),
                scope = BindingScope(match(node.required("app"), ::AppId), match(node.required("space"), ::SpaceId)),
                policy = PolicyId(node.required("policy").text()),
                expiresAt = node.field("expiresAt")?.instant()?.toEpochMilli(),
                breakGlass = node.field("breakGlass")?.bool() ?: false,
            )
        } catch (error: IllegalArgumentException) {
            throw SnapshotFormatException("${node.path}: binding [$id]: ${error.message}", error)
        }
    }

    private fun subject(node: DocumentNode, kind: DocumentKind, owner: String): SubjectRef {
        if (node.json.isString) {
            val text = node.text()
            if (text != WILDCARD) {
                node.fail("a subject is $WILDCARD, a principal or a role, not [$text]")
            }
            return SubjectRef.Anyone
        }
        node.requireObject("principal", "role", "tenant")
        val tenant = node.field("tenant")?.text()?.let(::TenantId) ?: if (kind == DocumentKind.TENANT) TenantId(owner) else null
        val principal = node.field("principal")
        val role = node.field("role")
        return when {
            principal != null && role != null -> node.fail("a subject is a principal or a role, not both")
            principal != null -> SubjectRef.Principal(
                tenant ?: node.fail("a principal of the $kind document names its tenant"),
                PrincipalId(principal.text()),
            )

            role != null -> SubjectRef.Role(tenant, RoleId(role.text()))
            else -> node.fail("a subject is $WILDCARD, a principal or a role")
        }
    }

    private fun <T> match(node: DocumentNode, of: (String) -> T): Match<T> {
        val text = node.text()
        if (text == WILDCARD) {
            return Match.Any
        }
        if (text.isEmpty() && node.path.endsWith(".app")) {
            node.fail("an app is an app id or $WILDCARD, not empty")
        }
        return Match.Exactly(of(text))
    }

    internal fun tree(binding: Binding, kind: DocumentKind, owner: String): ObjectNode {
        val node = factory.objectNode().put("id", binding.id.value)
        when (val subject = binding.subject) {
            SubjectRef.Anyone -> node.put("subject", WILDCARD)
            is SubjectRef.Principal -> node.putObject("subject").put("principal", subject.id.value)
                .put("tenant", subject.tenant.value)

            is SubjectRef.Role -> node.putObject("subject").put("role", subject.id.value).also { role ->
                // A tenant document reads a role without a tenant as its own: write it only when it differs.
                if (subject.tenant != null && !(kind == DocumentKind.TENANT && subject.tenant!!.value == owner)) {
                    role.put("tenant", subject.tenant!!.value)
                }
            }
        }
        node.put("app", binding.scope.app.text { it.value })
        node.put("space", binding.scope.space.text { it.value })
        node.put("policy", binding.policy.value)
        binding.expiresAt?.let { node.put("expiresAt", Instant.ofEpochMilli(it).toString()) }
        if (binding.breakGlass) node.put("breakGlass", true)
        return node
    }

    private inline fun <T> Match<T>.text(value: (T) -> String): String = when (this) {
        Match.Any -> WILDCARD
        is Match.Exactly -> value(this.value)
    }
}
