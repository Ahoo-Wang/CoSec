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
import me.ahoo.cosec.engine.CompileError
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.decision.DocumentKind

/**
 * `DOC-13` (R1B-3): no id a document writes holds a character [Identifiers.isUnprintableAt] (a control character or an
 * unpaired surrogate), the rule app and tenant ids already follow. An id reaches plain-text log lines (a host's route
 * escape review, a compile failure), metric tags, audit events and problem bodies; one holding a line break would forge
 * a line on every host that installs the document (RC1-15), and one holding an unpaired surrogate would read as another
 * id in UTF-8 (RC1-52). Checked here, on the document as written, each at its JSON path:
 *
 * - policy ids and statement names, binding ids, the boundary a document names;
 * - a binding's subject (principal, role, tenant), its space and the policy it binds;
 * - role ids, their app and space, the permissions they list;
 * - catalog permission ids (each names the policy `perm:<app>/<id>`);
 * - the external attributes the platform declares.
 *
 * Rate limit rule ids are held to a narrower rule already (`[A-Za-z0-9_.-]+`, `CompiledRateLimit.isValidId`), pattern
 * variable names by the pattern parser, attribute names a condition reads by its checker. Display text (a role's or a
 * catalog entry's `name` and `description`, a catalog group's `name`) is no id: it is served only in JSON, which escapes
 * it, and never printed in a log line, a tag or an event.
 */
@InternalCoSecApi
object DocumentIds {
    /** The errors of the ids of [document] that hold such a character, at their paths, as the compiler reports them. */
    fun unprintable(document: PolicyDocument): List<CompileError> = ArrayList<CompileError>(0).also { check(document, it) }

    internal fun check(document: PolicyDocument, errors: MutableList<CompileError>) {
        val check = Checker(errors)
        document.policies.forEachIndexed { p, policy ->
            check("$.policies[$p].id", "policy id", policy.id.value)
            policy.statements.forEachIndexed { s, statement ->
                check("$.policies[$p].statements[$s].name", "statement name", statement.name)
            }
        }
        document.boundary?.let { check(boundaryPath(document), "boundary", it.value) }
        document.bindings.forEachIndexed { b, binding ->
            val path = "$.bindings[$b]"
            check("$path.id", "binding id", binding.id.value)
            when (val subject = binding.subject) {
                is SubjectRef.Principal -> {
                    check("$path.subject.principal", "principal id", subject.id.value)
                    check("$path.subject.tenant", "tenant id", subject.tenant.value)
                }
                is SubjectRef.Role -> {
                    check("$path.subject.role", "role id", subject.id.value)
                    subject.tenant?.let { check("$path.subject.tenant", "tenant id", it.value) }
                }
                SubjectRef.Anyone -> Unit
            }
            (binding.scope.app as? Match.Exactly)?.let { check("$path.app", "app id", it.value.value) }
            (binding.scope.space as? Match.Exactly)?.let { check("$path.space", "space id", it.value.value) }
            check("$path.policy", "policy id", binding.policy.value)
        }
        document.roles.forEachIndexed { r, role ->
            val path = "$.roles[$r]"
            check("$path.id", "role id", role.id.value)
            role.app?.let { check("$path.app", "app id", it.value) }
            role.space?.let { check("$path.space", "space id", it.value) }
            (role.permissions as? RolePermissions.Listed)?.ids?.forEachIndexed { i, permission ->
                check("$path.permissions[$i]", "permission id", permission)
            }
        }
        document.catalog?.groups?.forEachIndexed { g, group ->
            group.permissions.forEachIndexed { p, permission ->
                check("$.catalog.groups[$g].permissions[$p].id", "permission id", permission.id)
            }
        }
        document.attributes?.forEachIndexed { a, attribute ->
            check("$.attributes[$a].path", "attribute", attribute.path)
        }
    }

    private fun boundaryPath(document: PolicyDocument): String =
        if (document.kind == DocumentKind.PLATFORM) "$.boundaries.default" else "$.boundary"

    private class Checker(private val errors: MutableList<CompileError>) {
        operator fun invoke(path: String, what: String, id: String) {
            if (!Identifiers.isPrintable(id)) {
                errors += CompileError(
                    path,
                    "the $what [${Identifiers.printable(id)}] holds a control character or an unpaired surrogate: an " +
                        "id reaches log lines, metric tags and audit events, which it must not forge (DOC-13)",
                )
            }
        }
    }
}
