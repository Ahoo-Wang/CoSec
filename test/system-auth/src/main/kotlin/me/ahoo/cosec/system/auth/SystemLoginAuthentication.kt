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

package me.ahoo.cosec.system.auth

import me.ahoo.cosec.login.Authentication
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.spi.identity.Identity

/** Signs in whoever a [SystemLogin] names, as it names itself; the token pair and its session are CoSec's. */
class SystemLoginAuthentication : Authentication<SystemLogin> {
    override val credentialsType: Class<SystemLogin> = SystemLogin::class.java

    override suspend fun authenticate(credentials: SystemLogin): TokenSubject =
        TokenSubject(
            id = credentials.username,
            kind = Identity.Credential.Kind.PERSON,
            app = credentials.app,
            tenant = credentials.tenant,
            roles = credentials.roles
        )
}
