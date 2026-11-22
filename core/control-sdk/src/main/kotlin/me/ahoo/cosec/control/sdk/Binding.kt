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
import java.time.Instant

/**
 * A binding of a document (the document format's `binding`): [subject] holds [policy] in [app] and [space] (`"*"` for
 * every one), until [expiresAt] if it is set. [breakGlass] marks a binding break-glass wrote; only break-glass writes one.
 */
@ExperimentalCoSecApi
class Binding(
    val id: String,
    val subject: Subject,
    val app: String,
    val space: String,
    val policy: String,
    val expiresAt: Instant? = null,
    val breakGlass: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = other is Binding && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = describe(
        "Binding",
        "id" to id,
        "subject" to subject,
        "app" to app,
        "space" to space,
        "policy" to policy,
        "expiresAt" to expiresAt,
        "breakGlass" to breakGlass,
    )

    private fun parts(): List<Any?> = listOf(id, subject, app, space, policy, expiresAt, breakGlass)
}
