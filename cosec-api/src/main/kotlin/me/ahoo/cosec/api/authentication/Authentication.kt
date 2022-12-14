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
package me.ahoo.cosec.api.authentication

import me.ahoo.cosec.api.principal.CoSecPrincipal
import reactor.core.publisher.Mono

/**
 * Authentication is a process in which a user provides credentials that are then compared to those stored in an operating system, database, app or resource.
 * If they match, users authenticate successfully, and can then perform actions that they're authorized for, during an authorization process.
 *
 * @author ahoo wang
 */
interface Authentication<C : Credentials, out P : CoSecPrincipal> {
    val supportCredentials: Class<C>
    fun authenticate(credentials: C): Mono<out P>
}
