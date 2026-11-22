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

/** A group of a [Catalog]: its [permissions], and whether they are held per space ([spaced]). */
@ExperimentalCoSecApi
class CatalogGroup(
    val name: String,
    permissions: List<CatalogPermission>,
    val spaced: Boolean = false,
    val description: String? = null,
) {
    val permissions: List<CatalogPermission> = permissions.toList()

    override fun equals(other: Any?): Boolean = other is CatalogGroup && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = describe(
        "CatalogGroup",
        "name" to name,
        "permissions" to permissions,
        "spaced" to spaced,
        "description" to description,
    )

    private fun parts(): List<Any?> = listOf(name, permissions, spaced, description)
}
