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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.Authentication
import me.ahoo.cosec.login.Credentials
import me.ahoo.cosec.spi.token.TokenVerifier

abstract class AbstractRefreshTokenAuthentication<C : RefreshTokenCredentials>(
    override val supportCredentials: Class<C>
) : Authentication<C>

class SimpleRefreshTokenAuthentication(private val tokenVerifier: TokenVerifier) :
    AbstractRefreshTokenAuthentication<RefreshTokenCredentials>(RefreshTokenCredentials::class.java) {
    private val blocking = Dispatchers.IO.limitedParallelism(BLOCKING_PARALLELISM)

    /** Off the caller's thread: [TokenVerifier.refresh] may read a revocation store over blocking I/O. */
    override suspend fun authenticate(credentials: RefreshTokenCredentials): TokenSubject =
        runInterruptible(blocking) { TokenSubject.of(tokenVerifier.refresh(credentials)) }

    private companion object {
        const val BLOCKING_PARALLELISM = 16
    }
}

interface RefreshTokenCredentials : Credentials, CompositeToken
