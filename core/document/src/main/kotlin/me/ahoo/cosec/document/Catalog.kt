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
import me.ahoo.cosec.engine.flat.ResourceSource

/**
 * An app's permission catalog (RFC 0004 §4.9, RFC 0012 §4): for UIs, generators and validation. An entry that writes
 * [CatalogPermission.actions] defines a permission; [RoleExpansion.effective] turns it into a policy, and evaluation
 * reads only that.
 */
@InternalCoSecApi
class Catalog(val groups: List<CatalogGroup>)

@InternalCoSecApi
class CatalogGroup(
    val name: String,
    val description: String? = null,
    /**
     * Whether the group's permissions apply in any space a role holding them applies in. A permission written with
     * `action` in a group that is not spaced applies only in the default space (RFC 0012 §4.2). Informational for a
     * hand-written policy, whose author writes that restriction.
     */
    val spaced: Boolean = false,
    val permissions: List<CatalogPermission>,
)

@InternalCoSecApi
class CatalogPermission(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    /** The action patterns of the permission this entry defines (RFC 0012 §4.1); null when it defines none. */
    val actions: List<String>? = null,
    /** Its resources, which need [actions]; empty for any resource. */
    val resources: List<ResourceSource> = emptyList(),
)
