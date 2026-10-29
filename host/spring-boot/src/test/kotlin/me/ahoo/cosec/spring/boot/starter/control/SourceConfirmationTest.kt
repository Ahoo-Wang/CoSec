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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.starter.control

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.ControlDecision
import me.ahoo.cosec.control.authz.ControlResources
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.RequestSecurity
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

/** The control plane's checks run only on documents the store has just confirmed (RFC 0007 §7.2). */
class SourceConfirmationTest {
    private val store = InMemorySnapshotDocumentStore()
    private val flaky = FlakyStore(store)
    private val clock = Clock.systemUTC()
    private val source = DocumentSnapshots.source(flaky, { _, document -> document.revision }, clock = clock)
    private val checker = mockk<AuthorizationChecker> {
        coEvery { decide(any(), any(), any(), any()) } returns mockk<Decision.Permit>()
    }
    private val authorizer = PipelineControlAuthorizer(checker, SourceConfirmation(source, clock))
    private val operator = Operator(
        actor = Actor(TenantId.PLATFORM, PrincipalId("admin")),
        memberships = Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList()),
        security = mockk<RequestSecurity>(),
    )

    private fun publish(revision: Long) {
        store.put(SnapshotKey.Platform, SnapshotDocument(1, revision, Instant.now(), byteArrayOf()))
    }

    private fun decide(): ControlDecision = runBlocking {
        authorizer.decide(operator, ControlActions.PUBLISH, ControlResources.document(SnapshotKey.Platform))
    }

    @Test
    fun `a failed reload after the first load is indeterminate, not the kept revision`() {
        publish(1)
        source.use {
            decide().assert().isEqualTo(ControlDecision.PERMIT)
            flaky.failing = true
            // The revocation the instance cannot read: the kept revision 1 must not answer the check.
            publish(2)
            decide().assert().isEqualTo(ControlDecision.INDETERMINATE)
            flaky.failing = false
            decide().assert().isEqualTo(ControlDecision.PERMIT)
        }
    }

    @Test
    fun `a cold read from a local copy is confirmed by a second read`() {
        flaky.localCopyAge = Duration.ofMinutes(1)
        publish(1)
        source.use {
            decide().assert().isEqualTo(ControlDecision.PERMIT)
        }
    }

    @Test
    fun `a document that cannot be compiled is indeterminate`() {
        publish(1)
        val failing = DocumentSnapshots.source(flaky, { _, _ -> error("cannot compile") }, clock = clock)
        failing.use {
            val decision = runBlocking {
                PipelineControlAuthorizer(checker, SourceConfirmation(it, clock))
                    .decide(operator, ControlActions.PUBLISH, ControlResources.document(SnapshotKey.Platform))
            }
            decision.assert().isEqualTo(ControlDecision.INDETERMINATE)
        }
    }

    private class FlakyStore(private val delegate: SnapshotDocumentStore) : SnapshotDocumentStore by delegate {
        @Volatile
        var failing: Boolean = false

        @Volatile
        var localCopyAge: Duration = Duration.ZERO

        override val localCopyMaxAge: Duration get() = localCopyAge

        override fun read(key: SnapshotKey): CompletableFuture<SnapshotRead> =
            if (failing) CompletableFuture.failedFuture(IllegalStateException("Redis is down")) else delegate.read(key)

        override fun readIfChanged(key: SnapshotKey, known: SnapshotDigest?): CompletableFuture<SnapshotRead> =
            if (failing) {
                CompletableFuture.failedFuture(IllegalStateException("Redis is down"))
            } else {
                delegate.readIfChanged(key, known)
            }
    }
}
