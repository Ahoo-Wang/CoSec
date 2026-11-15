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

package me.ahoo.cosec.runtime.call

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/** What a call to a port gave: a value, or why not. Failures stay values, so a stage decides what each one means (§7.4). */
internal sealed interface Answer<out T : Any> {
    class Value<out T : Any>(val value: T) : Answer<T>

    class Failed(val error: Throwable) : Answer<Nothing>
}

/** The value, or what [failed] makes of the failure. */
internal inline fun <T : Any> Answer<T>.valueOr(failed: (Throwable) -> T): T = when (this) {
    is Answer.Value -> value
    is Answer.Failed -> failed(error)
}

/**
 * Calls a port through [resumeOffIo], within [timeout] when there is one. An external cancellation propagates;
 * anything else that goes wrong, a CancellationException the port throws while the request is active included, is
 * [Answer.Failed] (RFC 0006 §7.4).
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T : Any> callPort(timeout: Duration?, port: suspend () -> T): Answer<T> = try {
    val value = if (timeout == null) resumeOffIo(port) else withLazyTimeoutOrNull(timeout) { resumeOffIo(port) }
    if (value == null) Answer.Failed(TimeoutException("No answer within $timeout.")) else Answer.Value(value)
} catch (e: CancellationException) {
    if (!currentCoroutineContext().isActive) {
        throw e
    }
    Answer.Failed(e)
} catch (e: Exception) {
    Answer.Failed(e)
}
