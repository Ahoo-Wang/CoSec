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
package me.ahoo.cosec.login

/**
 * What a login request presents to [Authentication]: a password, a refresh token, a social provider's code. Not
 * `me.ahoo.cosec.spi.authn.Credentials`, which is what an ordinary request carries (its token or API key) for the
 * pipeline's `Authenticator`.
 *
 * @see Authentication
 */
interface LoginCredentials
