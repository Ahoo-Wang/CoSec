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
 * A role of a document (the document format's `role`, RFC 0012): the catalog [permissions] its members hold. A tenant's
 * role names its [app] and, in a spaced app, the [space] it holds them in; an app's role names neither, since the
 * app is the document's.
 */
@ExperimentalCoSecApi
class Role(
    val id: String,
    val permissions: Permissions,
    val app: String? = null,
    val space: String? = null,
    val name: String? = null,
    val description: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is Role && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = describe(
        "Role",
        "id" to id,
        "permissions" to permissions,
        "app" to app,
        "space" to space,
        "name" to name,
        "description" to description,
    )

    private fun parts(): List<Any?> = listOf(id, permissions, app, space, name, description)
}

/** The catalog permissions a [Role] holds: every one of its app's (`"*"`, an app's role only), or the listed ids. */
@ExperimentalCoSecApi
sealed class Permissions {
    /** Every permission of the app's catalog: `"*"`. */
    @ExperimentalCoSecApi
    data object All : Permissions()

    /** The catalog permissions [ids]. */
    @ExperimentalCoSecApi
    class Of(val ids: List<String>) : Permissions() {
        override fun equals(other: Any?): Boolean = other is Of && ids == other.ids

        override fun hashCode(): Int = ids.hashCode()

        override fun toString(): String = describe("Of", "ids" to ids)
    }
}
