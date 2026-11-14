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
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.decision.DocumentKind

/** What a role grants (RFC 0012 §5). */
@InternalCoSecApi
sealed interface RolePermissions {
    /** `"*"`: every permission the catalog defines, as it grows. Only in an app document (RFC 0012 §5.1). */
    @InternalCoSecApi
    data object All : RolePermissions

    /** These permissions, by their catalog ids; possibly none. */
    @InternalCoSecApi
    data class Listed(val ids: List<String>) : RolePermissions
}

/**
 * A role of an app (RFC 0012 §5): which permissions it grants. A system role, in its app's document, has neither [app]
 * nor [space]; a custom role, in a tenant document, names its [app] and, optionally, the one [space] it applies in.
 */
@InternalCoSecApi
class RoleDefinition(
    val id: RoleId,
    val permissions: RolePermissions,
    val name: String? = null,
    val description: String? = null,
    val app: AppId? = null,
    val space: SpaceId? = null,
) {
    /** Why a document of [kind] cannot hold this role; null when it can. */
    @InternalCoSecApi
    fun shapeProblem(kind: DocumentKind): String? = when {
        kind == DocumentKind.PLATFORM -> "role [${id.value}]: the platform document has no roles"
        kind == DocumentKind.APP && (app != null || space != null) ->
            "role [${id.value}]: a system role belongs to its document's app and names no app or space"
        kind == DocumentKind.TENANT && app == null -> "role [${id.value}]: a tenant's role names its app"
        kind == DocumentKind.TENANT && permissions == RolePermissions.All ->
            "role [${id.value}]: \"*\" is only for a system role; a tenant's role lists its permissions"
        else -> null
    }
}
