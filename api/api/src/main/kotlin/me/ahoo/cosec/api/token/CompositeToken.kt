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

package me.ahoo.cosec.api.token

/**
 * The tokens of a login: the [accessToken] a request presents and the [refreshToken] that renews it. Two credentials,
 * so its string names neither, as `Credentials` does: a token that reaches a log line is not written
 * there. Equal by value.
 */
class CompositeToken(val accessToken: String, val refreshToken: String) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is CompositeToken && accessToken == other.accessToken && refreshToken == other.refreshToken)

    override fun hashCode(): Int = 31 * accessToken.hashCode() + refreshToken.hashCode()

    override fun toString(): String = "CompositeToken(accessToken=***, refreshToken=***)"
}
