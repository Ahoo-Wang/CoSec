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
 * A principal of a tenant, as the control API names who did something: a change's author, its approver, who confirmed
 * a statement. A platform operator's [tenant] is `(platform)`; a service caller's [principal] is `service:<name>`.
 */
@ExperimentalCoSecApi
class Actor(val tenant: String, val principal: String) {
    override fun equals(other: Any?): Boolean = other is Actor && tenant == other.tenant && principal == other.principal

    override fun hashCode(): Int = listOf(tenant, principal).hashCode()

    override fun toString(): String = describe("Actor", "tenant" to tenant, "principal" to principal)
}
