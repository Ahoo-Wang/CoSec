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

package me.ahoo.cosec.cocache.control

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.shadow.CoCacheShadowReports
import me.ahoo.cosec.cocache.snapshot.SnapshotNode
import me.ahoo.cosec.cocache.snapshot.newKeyPrefix
import me.ahoo.cosec.cocache.snapshot.rawStore
import me.ahoo.cosec.control.change.Base
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.document.shadow.ShadowCodec
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.shadow.InstalledDocument
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * `SCP-10` and `SCP-13` (#1403): a record an 8.0 preview stored under a tenant or app id that holds `/` is a format
 * error of the whole record, as any id no key can name is, and nothing skips it. Each failure names the record and the
 * id, so an operator can find what to remove (`upgrading.md`, previews).
 */
class SlashIdRecordsTest {
    private val backend = InMemoryBackend()

    private suspend fun formatError(block: suspend () -> Unit): SnapshotFormatException {
        val error = try {
            block()
            null
        } catch (e: Exception) {
            e
        }
        return generateSequence<Throwable>(checkNotNull(error) { "It did not fail." }) { it.cause }
            .filterIsInstance<SnapshotFormatException>().first()
    }

    private fun change(id: String) = ChangeRequest(
        id = ChangeId(id),
        key = SnapshotKey.Tenant("acme"),
        base = Base(0, null),
        operations = emptyList(),
        author = Actor(TenantId("acme"), PrincipalId("alice")),
        note = "",
        kind = ProvenanceKind.PUBLISH,
        state = ChangeState.DRAFT,
        findings = emptyList(),
        candidate = null,
        checkedWith = emptyMap(),
        createdAt = Instant.parse("2026-11-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-11-01T00:00:00Z"),
    )

    @Test
    fun `a back-reference set or a change of a slash id fails whole, naming the record and the id`() = runBlocking<Unit> {
        SnapshotNode(backend, newKeyPrefix()).use { node ->
            val store = CoCacheControlStore.create(node.factory, node.cache, node.keyPrefix)
            store.putChange(null, change("c1")).assert().isTrue()
            val written = ChangeRequestCodec.encode(change("c1")).decodeToString()
                .replace("\"key\":\"tenant:acme\"", "\"key\":\"tenant:acme/west\"")
            CacheRuntime.builder().backend(backend).build().use { raw ->
                raw.create(
                    rawStore("RawReferences", node.keyPrefix, CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME, ReferenceKeyEncoder)
                ).async().put(
                    ReferenceKey(SnapshotKey.Platform, PolicyId("shared")),
                    """{"tenant:acme":1,"tenant:acme/west":1}""".encodeToByteArray(),
                ).await()
                raw.create(
                    rawStore("RawChange", node.keyPrefix, CoSecCacheSpecs.CHANGE_REQUEST_CACHE_NAME, KeyEncoder<ChangeId> { it.value })
                ).async().put(ChangeId("c1"), written.encodeToByteArray()).await()
            }
            formatError { store.referrers(SnapshotKey.Platform, PolicyId("shared")) }.message.assert()
                .contains("The back-reference set of [platform] policy [shared]").contains("[tenant:acme/west]")
            for (read in listOf<suspend () -> Unit>({ store.change(ChangeId("c1")) }, { store.openChanges() })) {
                formatError(read).message.assert().contains("Change [c1]").contains("[acme/west]")
            }
        }
    }

    @Test
    fun `an instance status of a slash id fails the statuses, naming the instance and the id`() = runBlocking<Unit> {
        SnapshotNode(backend, newKeyPrefix()).use { node ->
            val sink = CoCacheShadowReports.create(node.factory, node.keyPrefix, Clock.systemUTC())
            val installed = mapOf<SnapshotKey, InstalledDocument>(SnapshotKey.Tenant("acme") to InstalledDocument(1, Duration.ZERO))
            sink.write(InstanceStatus("i1", Instant.now(), installed))
            val written = ShadowCodec.encodeStatuses(
                mapOf("i1" to InstanceStatus("i1", Instant.now(), installed))
            ).decodeToString().replace("tenant:acme", "tenant:acme/west")
            CacheRuntime.builder().backend(backend).build().use { raw ->
                raw.create(
                    rawStore("RawStatus", node.keyPrefix, CoSecCacheSpecs.INSTANCE_STATUS_CACHE_NAME, KeyEncoder<String> { it })
                ).async().put("i1", written.encodeToByteArray()).await()
            }
            formatError { sink.statuses() }.message.assert()
                .contains("The status of instance [i1]").contains("[acme/west]")
        }
    }
}
