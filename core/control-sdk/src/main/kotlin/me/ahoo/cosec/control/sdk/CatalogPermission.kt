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

/**
 * A permission of a [CatalogGroup]. With [actions] it is the short form a role grants directly (RFC 0012 §3); without,
 * a policy grants it.
 */
@ExperimentalCoSecApi
class CatalogPermission(
    val id: String,
    val name: String? = null,
    actions: List<String> = emptyList(),
    resources: List<Resource> = emptyList(),
    val description: String? = null,
) {
    val actions: List<String> = actions.toList()

    val resources: List<Resource> = resources.toList()

    override fun equals(other: Any?): Boolean = other is CatalogPermission && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String =
        describe(
            "CatalogPermission",
            "id" to id,
            "name" to name,
            "actions" to actions,
            "resources" to resources,
            "description" to description,
        )

    private fun parts(): List<Any?> = listOf(id, name, actions, resources, description)
}
