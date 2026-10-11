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

package me.ahoo.cosec.authorization

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.CoSecPrincipal.Companion.isRoot

/**
 * Decides which principals are root. Root principals bypass every authorization check.
 *
 * [DEFAULT] treats the principal whose id equals [CoSecPrincipal.ROOT_ID] (the `cosec.root` system property,
 * `cosec` by default) as root. Deployments where principal ids can be chosen by users should use [NONE] or a
 * matcher bound to a stronger signal.
 */
fun interface RootPrincipalMatcher {
    fun isRoot(principal: CoSecPrincipal): Boolean

    companion object {
        @JvmField
        val DEFAULT: RootPrincipalMatcher = RootPrincipalMatcher { it.isRoot }

        /** No principal is root. */
        @JvmField
        val NONE: RootPrincipalMatcher = RootPrincipalMatcher { false }
    }
}
