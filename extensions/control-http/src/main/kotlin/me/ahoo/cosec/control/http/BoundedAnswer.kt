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

package me.ahoo.cosec.control.http

import java.io.IOException
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

/**
 * The body of a control API answer, read as UTF-8 text up to [maxBytes] (DR1-F-17): a host, or whatever answers in its
 * place, that sends more fails the call with [IOException] as soon as it has, rather than filling the caller's heap. A
 * declared `Content-Length` over the bound fails before any byte is read.
 */
internal class BoundedAnswer(private val maxBytes: Long) : HttpResponse.BodyHandler<String> {
    override fun apply(info: HttpResponse.ResponseInfo): HttpResponse.BodySubscriber<String> {
        val declared = info.headers().firstValueAsLong(CONTENT_LENGTH).orElse(0L)
        return Counting(declared, HttpResponse.BodySubscribers.ofString(Charsets.UTF_8))
    }

    /** Counts what comes, and gives up past the bound: [declared] is the answer's `Content-Length`, 0 when it has none. */
    private inner class Counting(
        private val declared: Long,
        private val delegate: HttpResponse.BodySubscriber<String>,
    ) : HttpResponse.BodySubscriber<String> {
        private var subscription: Flow.Subscription? = null
        private var read = 0L
        private var over = false

        override fun getBody(): CompletionStage<String> = delegate.body

        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            if (declared > maxBytes) {
                refuse(declared)
            } else {
                delegate.onSubscribe(subscription)
            }
        }

        override fun onNext(item: List<ByteBuffer>) {
            if (over) return
            read += item.sumOf { it.remaining().toLong() }
            if (read > maxBytes) refuse(read) else delegate.onNext(item)
        }

        private fun refuse(bytes: Long) {
            over = true
            subscription?.cancel()
            delegate.onError(tooLarge(bytes))
        }

        override fun onError(throwable: Throwable) {
            if (!over) delegate.onError(throwable)
        }

        override fun onComplete() {
            if (!over) delegate.onComplete()
        }
    }

    private fun tooLarge(bytes: Long) = IOException(
        "The control plane's answer is over $maxBytes bytes ($bytes read or declared): no answer of the control API " +
            "is that large, so it is not read."
    )

    private companion object {
        const val CONTENT_LENGTH = "Content-Length"
    }
}
