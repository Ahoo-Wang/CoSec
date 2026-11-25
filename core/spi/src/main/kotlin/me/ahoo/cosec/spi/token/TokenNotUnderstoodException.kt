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

package me.ahoo.cosec.spi.token

import me.ahoo.cosec.model.CoSecException

/**
 * A token, its signature verified, whose JWS `crit` header names an extension this version does not understand
 * (RFC 7515 §4.1.11, `IDN-47`): a later issuer marked it as one a reader must not read without. It is not an answer
 * about the token: the instance is older than the issuer, and the same token passes once the instance is upgraded. The
 * pipeline answers `503` `unavailable`, as it does for an identity header whose `crit` this version does not know
 * (`IDN-41`), and a client must not take it for the end of its session.
 *
 * A [TokenVerifier] of your own throws it for a token whose `crit` names anything it does not understand; a `crit` that
 * is malformed is an invalid token, a [TokenVerificationException].
 */
class TokenNotUnderstoodException(message: String) : CoSecException(message)
