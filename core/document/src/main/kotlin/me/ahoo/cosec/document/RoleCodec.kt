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
import me.ahoo.cosec.document.DocumentJson.WILDCARD
import me.ahoo.cosec.document.DocumentJson.factory
import me.ahoo.cosec.document.DocumentJson.nonEmpty
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import tools.jackson.databind.node.ObjectNode

/** A role, both ways: what each kind of document may write is checked here. */
internal object RoleCodec {
    private val APP_ROLE_FIELDS = arrayOf("id", "name", "description", "permissions")
    private val TENANT_ROLE_FIELDS = APP_ROLE_FIELDS + arrayOf("app", "space")

    /**
     * A role (RFC 0012 §5.3). What each kind of document may write is checked here; what makes a value valid (an id
     * without `@`, an app id, a space that is not reserved) is the compiler's, at the same path.
     */
    internal fun role(node: DocumentNode, kind: DocumentKind): RoleDefinition {
        node.requireObject(*(if (kind == DocumentKind.TENANT) TENANT_ROLE_FIELDS else APP_ROLE_FIELDS))
        val id = node.required("id").text()
        return try {
            RoleDefinition(
                id = RoleId(id),
                permissions = rolePermissions(node.required("permissions"), kind),
                name = node.field("name")?.text(),
                description = node.field("description")?.text(),
                app = if (kind == DocumentKind.TENANT) AppId(node.required("app").nonEmpty()) else null,
                space = node.field("space")?.let { SpaceId(it.text()) },
            )
        } catch (error: IllegalArgumentException) {
            throw SnapshotFormatException("${node.path}: role [$id]: ${error.message}", error)
        }
    }

    /** A list of permission ids, or `"*"` in an app document. */
    private fun rolePermissions(node: DocumentNode, kind: DocumentKind): RolePermissions {
        if (node.json.isString) {
            if (kind == DocumentKind.APP && node.text() == WILDCARD) return RolePermissions.All
            node.fail(
                if (kind == DocumentKind.APP) {
                    "permissions is a list of permission ids or $WILDCARD"
                } else {
                    "a tenant's role lists its permissions: $WILDCARD is only for a system role"
                }
            )
        }
        return RolePermissions.Listed(node.elements().map { it.text() })
    }

    internal fun role(role: RoleDefinition): ObjectNode {
        val node = factory.objectNode().put("id", role.id.value)
        role.app?.let { node.put("app", it.value) }
        role.space?.let { node.put("space", it.value) }
        role.name?.let { node.put("name", it) }
        role.description?.let { node.put("description", it) }
        when (val permissions = role.permissions) {
            RolePermissions.All -> node.put("permissions", WILDCARD)
            is RolePermissions.Listed -> node.putArray("permissions").also { ids -> permissions.ids.forEach(ids::add) }
        }
        return node
    }
}
