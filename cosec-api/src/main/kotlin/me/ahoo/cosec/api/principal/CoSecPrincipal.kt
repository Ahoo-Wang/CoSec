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
package me.ahoo.cosec.api.principal

import me.ahoo.cosec.api.CoSec
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

    /**
     * @see id
     */
    override fun getName(): String {
        return id
    }

    val attrs: Map<String, Any>
    fun anonymous(): Boolean {
        return ANONYMOUS_ID == id
    }

    fun authenticated(): Boolean {
        return !anonymous()
    }

    companion object {

        //region ROOT 根账号拥有所有权限
        const val ROOT_KEY = "cosec.root"

        val ROOT_ID: String = System.getProperty(ROOT_KEY, CoSec.COSEC)

        //endregion
        //region ANONYMOUS 未认证状态下的用户

        val ANONYMOUS_ID = CoSec.DEFAULT

        fun CoSecPrincipal.isRoot(): Boolean {
            return ROOT_ID == id
        }
    }
}
