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

package me.ahoo.cosec.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Where the pipeline continues after an SPI call really suspended (RFC 0006 §7.2, T2). The WebFlux and Gateway
 * adapters put `ResumeDispatcher(Dispatchers.Default)` into the bridge's context next to `Dispatchers.Unconfined`, so a
 * request starts on the event loop without a dispatch and never goes on with CPU work on a client library's I/O thread.
 * The WebMvc adapter puts none: the continuation goes back through `runBlocking`'s interceptor to the request thread.
 */
class ResumeDispatcher(val dispatcher: CoroutineDispatcher) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ResumeDispatcher>

    override fun toString(): String = "ResumeDispatcher($dispatcher)"
}

/**
 * Calls [block], an SPI call, so that the pipeline goes on where RFC 0006 §7.2 says:
 *
 * - T1: when [block] completes without suspending, the caller continues at once on the same thread, with no dispatch.
 * - T2: when it suspends, the caller continues on the context's [ResumeDispatcher], or, without one, through the
 *   context's interceptor; never on the thread that completed [block].
 * - T3: only the context the adapter built decides; nothing here asks which host this is.
 *
 * [block] itself runs in the caller's context, so cancelling the caller cancels it.
 */
internal suspend fun <T> resumeOffIo(block: suspend () -> T): T = suspendCoroutineUninterceptedOrReturn { caller ->
    val resume = caller.context[ResumeDispatcher]?.dispatcher
    val completion = object : Continuation<T> {
        override val context: CoroutineContext get() = caller.context

        override fun resumeWith(result: Result<T>) {
            if (resume == null) {
                caller.intercepted().resumeWith(result)
            } else {
                resume.dispatch(caller.context) { caller.resumeWith(result) }
            }
        }
    }
    block.startCoroutineUninterceptedOrReturn(completion)
}
