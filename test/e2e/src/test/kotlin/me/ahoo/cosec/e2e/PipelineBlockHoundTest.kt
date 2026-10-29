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

package me.ahoo.cosec.e2e

import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.webflux.PipelineWebFilter
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import java.util.concurrent.CompletableFuture

/**
 * No blocking on the event loop (AGENTS.md; RFC 0006 §7.3): the WebFlux bridge over the warm slice decides, writes and
 * calls the chain on the thread that subscribed, without blocking. BlockHound watches the thread, as [BlockHoundWatch] sets it up.
 */
class PipelineBlockHoundTest {
    @Test
    fun `the WebFlux bridge decides and writes without blocking the event loop`() {
        Slice().use { slice ->
            slice.publish(
                SnapshotKey.Platform,
                ""","policies":[{"id":"p","statements":[{"effect":"allow","action":"http:GET","resource":"http:path:/public/**"}]}],""" +
                    """"bindings":[{"id":"g","subject":"*","app":"*","space":"*","policy":"p"}]"""
            )
            slice.publish(SnapshotKey.Tenant("acme"), "")
            val filter = PipelineWebFilter(slice.pipeline)
            fun exchange(path: String) = MockServerWebExchange.from(
                MockServerHttpRequest.get(path).header("Authorization", "Bearer alice@acme")
            )
            // Warm the source: the documents are compiled and installed before the watched calls.
            filter.filter(exchange("/public/a")) { Mono.empty() }.block()
            BlockHoundWatch.clear()
            val threads = BlockHoundWatch.onNonBlockingThread {
                val chained = CompletableFuture<String>()
                filter.filter(exchange("/public/a")) {
                    chained.complete(Thread.currentThread().name)
                    Mono.empty()
                }.subscribe()
                val denied = exchange("/private/a")
                filter.filter(denied) { error("never called") }.subscribe()
                check(chained.isDone) { "The warm permit did not reach the chain on the subscribing thread." }
                check(denied.response.isCommitted) { "The warm deny was not written on the subscribing thread." }
                chained.join()
            }
            threads.assert().startsWith(BlockHoundWatch.THREAD_PREFIX)
            BlockHoundWatch.violations.assert().isEmpty()
        }
    }
}
