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
import tools.jackson.databind.node.ObjectNode

/** An app document's catalog of permission groups, both ways; a permission's resources are [StatementCodec]'s. */
internal object CatalogCodec {
    internal fun decode(node: DocumentNode): Catalog {
        node.requireObject("groups")
        return Catalog(
            node.required("groups").elements().map { group ->
                group.requireObject("name", "description", "spaced", "permissions")
                CatalogGroup(
                    name = group.required("name").text(),
                    description = group.field("description")?.text(),
                    spaced = group.field("spaced")?.bool() ?: false,
                    permissions = group.required("permissions").elements().map { permission ->
                        permission.requireObject("id", "name", "description", "action", "resource")
                        CatalogPermission(
                            id = permission.required("id").text(),
                            name = permission.field("name")?.text(),
                            description = permission.field("description")?.text(),
                            actions = permission.field("action")?.strings(),
                            resources = permission.field("resource")?.let(StatementCodec::decodeResources).orEmpty(),
                        )
                    },
                )
            },
        )
    }

    internal fun tree(catalog: Catalog): ObjectNode {
        val node = factory.objectNode()
        val groups = node.putArray("groups")
        for (group in catalog.groups) {
            val item = groups.addObject().put("name", group.name)
            group.description?.let { item.put("description", it) }
            if (group.spaced) item.put("spaced", true)
            val permissions = item.putArray("permissions")
            for (permission in group.permissions) {
                val entry = permissions.addObject().put("id", permission.id)
                permission.name?.let { entry.put("name", it) }
                permission.description?.let { entry.put("description", it) }
                permission.actions?.let { actions ->
                    entry.putArray(
                        "action"
                    ).also { array -> actions.forEach(array::add) }
                }
                StatementCodec.putResources(entry, permission.resources)
            }
        }
        return node
    }
}
