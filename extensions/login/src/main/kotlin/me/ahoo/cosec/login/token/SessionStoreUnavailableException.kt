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

import me.ahoo.cosec.model.CoSecException
import kotlin.coroutines.cancellation.CancellationException

/**
 * A store that keeps sessions could not answer, or could not confirm a write: nothing was issued or spent, and nothing is
 * known about the pair (`IDN-19`). Thrown by this module's calls to the store ports, [me.ahoo.cosec.spi.token.TokenFamilyStore]
 * in [TokenFamilies] and [me.ahoo.cosec.spi.token.TokenStore] in [StoreTokenRevoker], with the store's own exception as
 * its cause, so that whoever answers the caller tells an unavailable store (503) from an error (500), as
 * [me.ahoo.cosec.spi.token.RevocationUnavailableException] does for a revocation read. An application that serves its
 * own refresh, switch or logout answers it as the starter's route does: 503, nothing issued, the client keeps its pair.
 */
class SessionStoreUnavailableException internal constructor(message: String, cause: Throwable) :
    CoSecException(message, cause)

/**
 * One call to a session store port: whatever it throws leaves as [SessionStoreUnavailableException], a store's exception
 * having no type of its own (a Redis client's, a timeout), and cancellation as itself.
 */
// stays: a store port may throw anything, and every failure of it is one answer
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> sessionStore(operation: String, call: () -> T): T = try {
    call()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw SessionStoreUnavailableException("The session store could not $operation.", e)
}
