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
 * What a tenant's or an app's document holds, without the header the control plane writes (its revision, when it was
 * published and by which change): what `GET /documents/{key}` reads and `:apply` makes the head into. A tenant's
 * document has no [catalog]; an app's has no [boundary]. The platform document's sections (limits, attributes,
 * boundaries) are not here: it stays an operator's.
 */
@ExperimentalCoSecApi
class DocumentContent(
    policies: List<Policy> = emptyList(),
    bindings: List<Binding> = emptyList(),
    roles: List<Role> = emptyList(),
    val catalog: Catalog? = null,
    rateLimits: List<RateLimitRule> = emptyList(),
    val boundary: String? = null,
) {
    val policies: List<Policy> = policies.toList()

    val bindings: List<Binding> = bindings.toList()

    val roles: List<Role> = roles.toList()

    val rateLimits: List<RateLimitRule> = rateLimits.toList()

    override fun equals(other: Any?): Boolean = other is DocumentContent && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = describe(
        "DocumentContent",
        "policies" to policies,
        "bindings" to bindings,
        "roles" to roles,
        "catalog" to catalog,
        "rateLimits" to rateLimits,
        "boundary" to boundary,
    )

    private fun parts(): List<Any?> = listOf(policies, bindings, roles, catalog, rateLimits, boundary)
}
