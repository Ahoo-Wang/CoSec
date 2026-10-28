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

package me.ahoo.cosec.snapshot

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

class SnapshotTypesTest {
    @Test
    fun keys() {
        SnapshotKey.Platform.kind.assert().isEqualTo(SnapshotKind.PLATFORM)
        SnapshotKey.Platform.toString().assert().isEqualTo("platform")
        APP_A.kind.assert().isEqualTo(SnapshotKind.APP)
        APP_A.toString().assert().isEqualTo("app:app-a")
        SnapshotKey.App("app-a").assert().isEqualTo(APP_A)
        SnapshotKey.App.isValidAppId("a:b").assert().isFalse()
        assertThrows<IllegalArgumentException> { SnapshotKey.App("a:b") }
        assertThrows<IllegalArgumentException> { SnapshotKey.App("") }
    }

    @Test
    fun documentCopiesItsBody() {
        val body = "abc".encodeToByteArray()
        val document = SnapshotDocument(1, 3, Instant.EPOCH, body)
        body[0] = 'x'.code.toByte()
        document.bodyBytes().decodeToString().assert().isEqualTo("abc")
        document.bodyBytes()[0] = 'y'.code.toByte()
        document.bodyStream().readAllBytes().decodeToString().assert().isEqualTo("abc")
        document.bodySize.assert().isEqualTo(3)
        document.toString().assert().contains("revision=3")
    }

    @Test
    fun documentEquality() {
        val document = SnapshotDocument(1, 3, Instant.EPOCH, "abc".encodeToByteArray())
        document.assert().isEqualTo(document)
        document.assert().isEqualTo(SnapshotDocument(1, 3, Instant.EPOCH, "abc".encodeToByteArray()))
        document.hashCode().assert().isEqualTo(
            SnapshotDocument(1, 3, Instant.EPOCH, "abc".encodeToByteArray()).hashCode()
        )
        document.assert().isNotEqualTo(SnapshotDocument(2, 3, Instant.EPOCH, "abc".encodeToByteArray()))
        document.assert().isNotEqualTo(SnapshotDocument(1, 4, Instant.EPOCH, "abc".encodeToByteArray()))
        document.assert().isNotEqualTo(SnapshotDocument(1, 3, Instant.MAX, "abc".encodeToByteArray()))
        document.assert().isNotEqualTo(SnapshotDocument(1, 3, Instant.EPOCH, "abd".encodeToByteArray()))
        document.assert().isNotEqualTo("abc")
    }

    @Test
    fun documentRevisionIsPositive() {
        assertThrows<IllegalArgumentException> { SnapshotDocument(1, 0, Instant.EPOCH, ByteArray(0)) }
    }

    @Test
    fun digest() {
        val digest = SnapshotDigest(byteArrayOf(0x0a, 0x1b))
        digest.toString().assert().isEqualTo("0a1b")
        digest.assert().isEqualTo(SnapshotDigest(byteArrayOf(0x0a, 0x1b)))
        digest.hashCode().assert().isEqualTo(SnapshotDigest(byteArrayOf(0x0a, 0x1b)).hashCode())
        digest.assert().isNotEqualTo(SnapshotDigest(byteArrayOf(0x0a)))
        digest.toByteArray().assert().containsExactly(0x0a, 0x1b)
    }

    @Test
    fun reads() {
        val present = SnapshotRead.Present(document(2), SnapshotDigest(byteArrayOf(1)))
        present.toString().assert().isEqualTo("Present(revision=2, digest=01)")
    }

    @Test
    fun optionsAreValidated() {
        SnapshotOptions().coldLoadTimeout.assert().isEqualTo(Duration.ofSeconds(2))
        assertThrows<IllegalArgumentException> { SnapshotOptions(revalidateInterval = Duration.ofSeconds(-1)) }
        assertThrows<IllegalArgumentException> { SnapshotOptions(lagWarn = Duration.ofMinutes(10)) }
        assertThrows<IllegalArgumentException> { SnapshotOptions(failClosedMaxLag = Duration.ofSeconds(-1)) }
        assertThrows<IllegalArgumentException> { SnapshotOptions(coldLoadTimeout = Duration.ZERO) }
        assertThrows<IllegalArgumentException> { SnapshotOptions(maxApps = 0) }
        assertThrows<IllegalArgumentException> { SnapshotOptions(compileThreads = 0) }
        assertThrows<IllegalArgumentException> { SnapshotOptions(compileQueueCapacity = 0) }
        assertThrows<IllegalArgumentException> { SnapshotOptions(reloadConcurrency = 0) }
        assertThrows<IllegalArgumentException> { SnapshotOptions(prewarmInitialBackoff = Duration.ofMinutes(1)) }
    }

    @Test
    fun noopMetricsAcceptEveryEvent() {
        val metrics = SnapshotMetrics.NOOP
        metrics.onLoad(SnapshotKind.APP, LoadOutcome.HIT)
        metrics.onCompile(SnapshotKind.APP, Duration.ZERO, true)
        metrics.onRegression(APP_A, 2, 1)
        metrics.onDocumentLost(APP_A, 2)
        metrics.onLagExceeded(APP_A, Duration.ZERO, false)
    }

    @Test
    fun inMemoryStore() {
        val store = InMemorySnapshotDocumentStore()
        val changes = mutableListOf<Collection<SnapshotKey>>()
        var resets = 0
        val subscription = store.subscribe(object : SnapshotChangeListener {
            override fun onChanged(keys: Collection<SnapshotKey>) {
                changes.add(keys)
            }

            override fun onReset() {
                resets++
            }
        })

        store.read(APP_A).join().assert().isEqualTo(SnapshotRead.Absent)
        store.readIfChanged(APP_A, null).join().assert().isEqualTo(SnapshotRead.Absent)

        val digest = store.put(APP_A, document(1))
        store[APP_A].assert().isEqualTo(document(1))
        (store.read(APP_A).join() as SnapshotRead.Present).digest.assert().isEqualTo(digest)
        store.readIfChanged(APP_A, digest).join().assert().isEqualTo(SnapshotRead.Unchanged)
        store.readIfChanged(APP_A, null).join().assert().isInstanceOf(SnapshotRead.Present::class.java)

        val next = store.put(APP_A, document(2))
        next.assert().isNotEqualTo(digest)
        (store.readIfChanged(APP_A, digest).join() as SnapshotRead.Present).document.revision.assert().isEqualTo(2)

        store.remove(APP_A)
        store[APP_A].assert().isNull()
        store.reset()
        changes.assert().containsExactly(listOf(APP_A), listOf(APP_A), listOf(APP_A))
        resets.assert().isEqualTo(1)

        subscription.close()
        store.put(APP_A, document(3))
        changes.assert().hasSize(3)
    }

    @Test
    fun digestCoversEveryField() {
        val base = InMemorySnapshotDocumentStore.digestOf(document(1, "x"))
        base.assert().isEqualTo(InMemorySnapshotDocumentStore.digestOf(document(1, "x")))
        InMemorySnapshotDocumentStore.digestOf(document(1, "y")).assert().isNotEqualTo(base)
        InMemorySnapshotDocumentStore.digestOf(document(2, "x")).assert().isNotEqualTo(base)
        InMemorySnapshotDocumentStore.digestOf(
            SnapshotDocument(1, 1, Instant.parse("2026-10-21T08:00:00Z"), "x".encodeToByteArray())
        )
            .assert().isNotEqualTo(base)
    }
}
