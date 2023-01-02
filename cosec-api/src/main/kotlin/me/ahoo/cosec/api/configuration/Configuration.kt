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

package me.ahoo.cosec.api.configuration

interface Configuration {
    fun get(key: String): Configuration?
    fun getRequired(key: String): Configuration = requireNotNull(get(key)) {
        "Configuration[$key] is required!"
    }

    fun asList(): List<Configuration>
    fun asMap(): Map<String, Configuration>
    fun asString(): String
    fun asBoolean(): Boolean
    fun asInt(): Int
    fun asLong(): Long
    fun <T> asPojo(pojoClass: Class<T>): T

    fun has(key: String): Boolean = get(key) != null
    fun asStringList(): List<String> = asList().map { it.asString() }
    fun asStringMap(): Map<String, String> = asMap().mapValues { it.value.asString() }
}

inline fun <reified T> Configuration.asPojo(): T = asPojo(T::class.java)
