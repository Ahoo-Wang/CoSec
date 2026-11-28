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

package me.ahoo.cosec.cocache

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.control.AppListCodec
import me.ahoo.cosec.cocache.control.ChangeRequestCacheCodec
import me.ahoo.cosec.cocache.control.ListedCodec
import me.ahoo.cosec.cocache.control.ReferrerMemberCodec
import me.ahoo.cosec.cocache.control.RevisionIndexCodec
import me.ahoo.cosec.cocache.control.StageSlotCodec
import me.ahoo.cosec.cocache.shadow.InstanceIndexCodec
import me.ahoo.cosec.cocache.shadow.InstanceStatusCodec
import me.ahoo.cosec.cocache.shadow.ReportersCodec
import me.ahoo.cosec.cocache.shadow.ResidentDocumentsCodec
import me.ahoo.cosec.cocache.shadow.ShadowReportCodec
import me.ahoo.cosec.cocache.shadow.StoredCandidateCodec
import me.ahoo.cosec.cocache.snapshot.SnapshotDocumentCodec
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.RevocationUnavailableException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * CoCache 8.2.0 (CoCache#997): stored content that decodes to `null` fails the read instead of reading as absent. What
 * that means for each of CoSec's stores, whose content only CoSec writes and a JSON `null` is never what it wrote.
 */
class NullPayloadTest {
    private val nul = "null".encodeToByteArray()

    /** CoSec's own codecs: none decodes `null` to a value, so CoCache's change does not reach them. */
    private val own: Map<String, Codec<*>> = mapOf(
        "snapshot and revision record" to SnapshotDocumentCodec,
        "revision index" to RevisionIndexCodec,
        "reference index member" to ReferrerMemberCodec,
        "change request" to ChangeRequestCacheCodec,
        "stage slot" to StageSlotCodec,
        "document list" to ListedCodec,
        "app list" to AppListCodec,
        "candidate" to StoredCandidateCodec,
        "shadow report" to ShadowReportCodec,
        "reporters" to ReportersCodec,
        "instance status" to InstanceStatusCodec,
        "resident documents" to ResidentDocumentsCodec,
        "instance index" to InstanceIndexCodec,
        "token family" to TokenFamilyCodec,
    )

    @Test
    fun `none of CoSec's own codecs reads a JSON null as a value`() {
        own.forEach { (store, codec) ->
            val decoded = runCatching { codec.decode(nul) }
            decoded.isFailure.assert().describedAs("$store decoded null to ${decoded.getOrNull()}").isTrue()
        }
    }

    @Test
    fun `the revocation tables' Jackson codecs decode a JSON null to null, the case CoCache now refuses`() {
        CoSecCacheSpecs.jacksonCodec<Boolean>(CoSecJson.mapper).decode(nul).assert().isNull()
        CoSecCacheSpecs.jacksonCodec<Long>(CoSecJson.mapper).decode(nul).assert().isNull()
    }

    private val backend = InMemoryBackend()
    private val keyPrefix = CoSecTestCaches.newKeyPrefix()
    private val foreign = CacheRuntime.builder().backend(backend).build()
    private val reading = CoSecTestCaches(backend, keyPrefix)

    @AfterEach
    fun close() {
        reading.close()
        foreign.close()
    }

    /** A writer that is not CoSec: it stores a JSON `null` under [cacheName]'s layout, as no CoSec instance does. */
    private fun <V : Any> writeNull(cacheName: String, key: String) {
        val layout = CoSecCacheSpecs.layout(keyPrefix, cacheName)
        val writesNull = object : Codec<V> {
            override fun encode(value: V): ByteArray = nul

            override fun decode(bytes: ByteArray): V = error("not read")
        }
        val store = foreign.create(
            StoreSpec.builder<String, V>(cacheName)
                .keyPrefix(layout.keyPrefix)
                .namespace(layout.namespace)
                .keyEncoder(KeyEncoder.ofToString())
                .codec(writesNull)
                .build(),
        )
        @Suppress("UNCHECKED_CAST")
        store.put(key, (if (cacheName == CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME) true else 1L) as V)
    }

    @Test
    fun `a revocation stored as a JSON null fails the read as foreign content, never reads as a token not revoked`() =
        runBlocking<Unit> {
            val reader = CoCacheRevocationReader(reading.revokedTokenCache, reading.revokedSubjectCache)
            writeNull<Boolean>(CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, RevokedTokenCache.key("t-1"))
            val tenant = TenantId("acme")
            val alice = PrincipalId("alice")
            writeNull<Long>(CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME, RevokedSubjectCache.key(tenant, alice))
            // Before CoCache 8.2.0 both read as absent: "not revoked", the token accepted. Now the content is foreign:
            // an error of its own, not an unavailable store, so not even the pipeline's verification lets the token
            // through on it (IDN-14 covers only RevocationUnavailableException); the entry stays in Redis.
            listOf<suspend () -> Any?>({ reader.isRevoked("t-1") }, { reader.subjectRevokedBefore(tenant, alice) })
                .forEach { read ->
                    val error = runCatching { read() }.exceptionOrNull()
                    error.assert().isInstanceOf(CacheReadException::class.java)
                    (error is RevocationUnavailableException).assert().isFalse()
                    generateSequence(error) { it.cause }.any { it is ForeignFormatException }.assert()
                        .describedAs("$error").isTrue()
                }
        }
}
