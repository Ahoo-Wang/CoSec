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

package me.ahoo.cosec.api.authorization

interface AuthorizeResult {
    val authorized: Boolean
    val reason: String

    companion object {
        val ALLOW: AuthorizeResult = allow("Allow")
        val EXPLICIT_DENY: AuthorizeResult = deny("Explicit Deny")
        val IMPLICIT_DENY: AuthorizeResult = deny("Implicit Deny")
        fun allow(reason: String): AuthorizeResult = AuthorizeResultData(true, reason)
        fun deny(reason: String): AuthorizeResult = AuthorizeResultData(false, reason)
    }
}

internal data class AuthorizeResultData(
    override val authorized: Boolean,
    override val reason: String,
) : AuthorizeResult
