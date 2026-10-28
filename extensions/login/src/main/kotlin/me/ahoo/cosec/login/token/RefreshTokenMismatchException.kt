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

package me.ahoo.cosec.login.token

import me.ahoo.cosec.spi.token.TokenVerificationException
import me.ahoo.cosec.spi.token.TokenVerifier

/**
 * Exception thrown when a refresh token was not issued with the access token it is presented with.
 *
 * Both tokens may be valid on their own; the pair is not, so the refresh is rejected as an invalid token (`401`).
 *
 * @see TokenVerifier.refresh
 */
class RefreshTokenMismatchException : TokenVerificationException(
    "The refresh token does not belong to the access token."
)
