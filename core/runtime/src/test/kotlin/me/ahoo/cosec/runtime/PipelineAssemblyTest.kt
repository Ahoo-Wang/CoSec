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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

/** The documented assembly of a host without Spring Boot: only stable API, the documents from a store. */
class PipelineAssemblyTest {
    private val publishedAt = "2026-10-26T00:00:00Z"

    private fun document(body: String) = SnapshotDocument(
        2,
        1,
        Instant.parse(publishedAt),
        """{"schema":2,"revision":1,"publishedAt":"$publishedAt"$body}""".encodeToByteArray(),
    )

    private fun request(path: String) = HttpFacts(
        method = "GET",
        rawPath = path,
        rawQuery = null,
        headers = Headers.EMPTY,
        cookies = Cookies.EMPTY,
        remoteAddress = "10.0.0.1",
        host = HostScope(null),
    )

    @Test
    fun `a pipeline assembled by hand decides from the documents of its store`() = runBlocking<Unit> {
        val store = InMemorySnapshotDocumentStore()
        store.put(
            SnapshotKey.Platform,
            document(
                ""","policies":[{"id":"public","statements":[{"effect":"allow","action":"http:GET","resource":"http:path:/public/**"}]}],""" +
                    """"bindings":[{"id":"public","subject":"*","app":"*","space":"*","policy":"public"}]""",
            ),
        )
        SuspendingSnapshotSource.of(store).use { snapshots ->
            val pipeline = Pipeline(PipelineComponents(Authenticator { AuthnResult.Anonymous }, snapshots))
            pipeline.evaluate(request("/public/x")).proceed.assert().isTrue()
            pipeline.evaluate(request("/orders/1")).status.assert().isEqualTo(401)
        }
    }
}
