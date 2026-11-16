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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.runtime

import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.PipelineFixtures.IMPLICIT_DENY
import me.ahoo.cosec.runtime.PipelineFixtures.StubEngine
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.PipelineFixtures.pipeline
import me.ahoo.cosec.runtime.PipelineFixtures.viewFor
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `SCP-13` (#1403) through the pipeline: a tenant or app id is one path segment, so a token that names a tenant or an
 * app with `/` (one a 7.x or preview issuer may still hold) is an invalid credential, 401 `token-invalid`. The request
 * is decided as anonymous in the default tenant and never reads the documents of `acme/west`, nor of `acme`.
 */
class OneSegmentIdPipelineTest {
    private class Verifier(private val token: () -> VerifiedToken) : TokenVerifier {
        override fun verify(accessToken: String): VerifiedToken = token()

        override fun refresh(token: CompositeToken): VerifiedToken = throw UnsupportedOperationException()
    }

    private class RecordingViews : SnapshotViews {
        val keys = CopyOnWriteArrayList<ViewKey>()

        override fun peek(key: ViewKey): SnapshotView = viewFor(key).also { keys += key }

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }

    private val withToken = facts(headers = mapOf("Authorization" to listOf("Bearer token")))

    @Test
    fun `a token of a tenant or an app with a slash is token-invalid and reads no tenant's documents`() = runTest {
        val tokens = listOf(
            { VerifiedToken("jti", "alice", "acme/west", null) },
            { VerifiedToken("jti", "alice", "acme", "order/v2") },
        )
        for (token in tokens) {
            val views = RecordingViews()
            val engine = StubEngine(IMPLICIT_DENY)
            val outcome = pipeline(TokenAuthenticator(Verifier(token)), engine, views).evaluate(withToken)

            outcome.status.assert().isEqualTo(ProblemType.TOKEN_INVALID.status)
            outcome.problem!!.type.assert().isEqualTo(ProblemType.TOKEN_INVALID)
            views.keys.map { it.tenant }.assert().containsOnly(TenantId.DEFAULT)
            engine.requests.single().request.subject.authenticated.assert().isFalse()
        }
    }
}
