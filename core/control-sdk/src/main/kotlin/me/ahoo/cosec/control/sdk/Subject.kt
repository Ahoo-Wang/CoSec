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
 * Whom a [Binding] binds (the document format's `subject`): anyone (`"*"`), a principal, or the members of a role. A
 * [tenant] is written only where the document's owner does not already say it (a platform binding).
 */
@ExperimentalCoSecApi
sealed class Subject {
    /** Every caller, signed in or not: `"*"`. */
    @ExperimentalCoSecApi
    data object Anyone : Subject()

    /** One principal: `{"principal": id}`. */
    @ExperimentalCoSecApi
    class Principal(val id: String, val tenant: String? = null) : Subject() {
        override fun equals(other: Any?): Boolean = other is Principal && id == other.id && tenant == other.tenant

        override fun hashCode(): Int = listOf("principal", id, tenant).hashCode()

        override fun toString(): String = describe("Principal", "id" to id, "tenant" to tenant)
    }

    /** The members of a role: `{"role": id}`. */
    @ExperimentalCoSecApi
    class Role(val id: String, val tenant: String? = null) : Subject() {
        override fun equals(other: Any?): Boolean = other is Role && id == other.id && tenant == other.tenant

        override fun hashCode(): Int = listOf("role", id, tenant).hashCode()

        override fun toString(): String = describe("Role", "id" to id, "tenant" to tenant)
    }
}
