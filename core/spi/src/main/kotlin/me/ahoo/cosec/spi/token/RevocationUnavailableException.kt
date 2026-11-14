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

/**
 * A [RevocationReader] could not read its store (Redis down, a read that timed out), so it cannot say whether a token
 * was revoked. It is not an answer: what it means is the caller's to decide. The pipeline's `TokenAuthenticator`,
 * verifying a token it did not issue, lets that token through (the documented exception to failing closed); everything
 * that issues a token, refresh above all, fails instead (`IDN-14`).
 *
 * Any other exception a reader throws, content it cannot decode included, is an error, never an unavailable store.
 */
class RevocationUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
