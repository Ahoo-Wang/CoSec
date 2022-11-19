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
package me.ahoo.cosec.role

/**
 * Role tools.
 *
 * @author ahoo wang
 */
object RoleConvert {
    const val SEPARATOR = ","

    @JvmStatic
    fun asString(roles: Iterable<String>): String {
        return roles.joinToString(SEPARATOR)
    }

    @JvmStatic
    fun asSet(roles: String): Set<String> {
        return roles.split(SEPARATOR).toSet()
    }
}
