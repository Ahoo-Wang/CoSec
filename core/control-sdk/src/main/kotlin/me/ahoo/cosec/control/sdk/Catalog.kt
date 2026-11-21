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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.ExperimentalCoSecApi

/** An app's catalog (the document format's `catalog`, RFC 0012): the permissions its roles are made of, in groups. */
@ExperimentalCoSecApi
class Catalog(val groups: List<CatalogGroup>) {
    override fun equals(other: Any?): Boolean = other is Catalog && groups == other.groups

    override fun hashCode(): Int = groups.hashCode()

    override fun toString(): String = describe("Catalog", "groups" to groups)
}

/** A group of a [Catalog]: its [permissions], and whether they are held per space ([spaced]). */
@ExperimentalCoSecApi
class CatalogGroup(val name: String, val permissions: List<CatalogPermission>, val spaced: Boolean = false) {
    override fun equals(other: Any?): Boolean =
        other is CatalogGroup && name == other.name && permissions == other.permissions && spaced == other.spaced

    override fun hashCode(): Int = listOf(name, permissions, spaced).hashCode()

    override fun toString(): String =
        describe("CatalogGroup", "name" to name, "permissions" to permissions, "spaced" to spaced)
}

/**
 * A permission of a [CatalogGroup]. With [actions] it is the short form a role grants directly (RFC 0012 §3); without,
 * a policy grants it.
 */
@ExperimentalCoSecApi
class CatalogPermission(
    val id: String,
    val name: String? = null,
    val actions: List<String> = emptyList(),
    val resources: List<Resource> = emptyList(),
) {
    override fun equals(other: Any?): Boolean = other is CatalogPermission && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String =
        describe("CatalogPermission", "id" to id, "name" to name, "actions" to actions, "resources" to resources)

    private fun parts(): List<Any?> = listOf(id, name, actions, resources)
}
