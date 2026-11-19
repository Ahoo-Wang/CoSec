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

import me.ahoo.cosec.login.LoginCredentials

/**
 * What a scenario signs in with: the user names itself, its tenant, the app its token is for and its roles. The system
 * test checks how the deployed processes decide on a CoSec token, not how a login checks a password, so there is none:
 * this host runs only on the system test's closed network, and must never be anything else.
 */
data class SystemLogin(
    val username: String,
    val tenant: String?,
    val app: String?,
    val roles: Set<String>
) : LoginCredentials
