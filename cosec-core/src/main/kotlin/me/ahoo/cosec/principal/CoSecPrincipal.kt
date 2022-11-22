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

import me.ahoo.cosec.CoSec
import me.ahoo.cosec.internal.InternalIds.wrap
import me.ahoo.cosec.policy.PolicyCapable
import java.security.Principal

/**
 * A person or automated agent.
 *
 * @author ahoo wang
 * @see java.security.Principal
 */
interface CoSecPrincipal : Principal, PolicyCapable, RoleCapable {
    //endregion
    val id: String

    override fun getName(): String

    val attrs: Map<String, Any>
    fun anonymous(): Boolean {
        return ANONYMOUS_ID == id
    }

    fun authenticated(): Boolean {
        return !anonymous()
    }

    companion object {
        const val NAME_KEY = "name"

        //region ROOT 根账号拥有所有权限
        const val ROOT_KEY = "cosec.root"

        val ROOT_NAME: String = System.getProperty(ROOT_KEY, CoSec.COSEC)

        //endregion
        //region ANONYMOUS 未认证状态下的用户

        val ANONYMOUS_ID = CoSec.DEFAULT

        val ANONYMOUS_NAME = wrap("anonymous")

        val ANONYMOUS: CoSecPrincipal = SimplePrincipal(ANONYMOUS_ID, ANONYMOUS_NAME)

        fun CoSecPrincipal.isRoot(): Boolean {
            return ROOT_NAME == name
        }
    }
}
