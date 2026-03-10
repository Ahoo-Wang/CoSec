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
package me.ahoo.cosec.principal

import me.ahoo.cosec.api.principal.CoSecPrincipal

/**
 * Simple implementation of [CoSecPrincipal].
 *
 * This is a basic implementation that stores principal data
 * as simple properties.
 *
 * @param id The unique identifier for this principal
 * @param policies Set of policy IDs assigned to this principal
 * @param roles Set of role IDs assigned to this principal
 * @param attributes Additional attributes for this principal
 *
 * @see CoSecPrincipal
 */
data class SimplePrincipal(
    override val id: String,
    override val policies: Set<String> = emptySet(),
    override val roles: Set<String> = emptySet(),
    override val attributes: Map<String, Any> = emptyMap()
) : CoSecPrincipal {
    companion object {
        /** Anonymous principal for unauthenticated requests */
        @JvmField
        val ANONYMOUS: CoSecPrincipal = SimplePrincipal(CoSecPrincipal.ANONYMOUS_ID)
    }
}
