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

package me.ahoo.cosec.migrate

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.codec.ByteArrayCodec
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.control.CoCacheControlStore
import me.ahoo.cosec.cache.createLocked
import me.ahoo.cosec.cache.snapshot.createPolicySnapshotCache
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * `cosec-migrate cocache8` on real Redis: a CoSec 8.0.0-alpha deployment's data, written as CoCache 6 wrote it (format
 * 1), is moved to CoCache 8: documents published again, revocations written again with their remaining lifetime,
 * everything else deleted; and nothing is written while a document cannot be moved or a change is in flight.
 */
class CoCache8UpgradeTest {
    private val keyPrefix = "migrate-test:${UUID.randomUUID()}"
    private val client = RedisClient.create(REDIS)
    private val redis = client.connect(ByteArrayCodec.INSTANCE)

    @TempDir
    lateinit var temp: Path

    @AfterEach
    fun close() {
        redis.close()
        client.shutdown()
    }

    private fun key(cacheName: String, encodedKey: String): String =
        "${CoSecCacheSpecs.storagePath(keyPrefix, cacheName)}:$encodedKey"

    private fun cocache6(key: String, payload: String, state: Byte = CoCache6Format.STATE_VALUE, ttlMillis: Long? = null) {
        redis.sync().set(key.encodeToByteArray(), CoCache6.value(payload.encodeToByteArray(), state))
        ttlMillis?.let { redis.sync().pexpire(key.encodeToByteArray(), it) }
    }

    private fun raw(key: String): ByteArray? = redis.sync().get(key.encodeToByteArray())

    private fun run(vararg args: String): Pair<Int, String> {
        val bytes = ByteArrayOutputStream()
        val code = MigrateMain.run(args.toList(), PrintStream(bytes, true))
        return code to bytes.toString()
    }

    private fun cocache8(vararg extra: String): Pair<Int, String> =
        run("cocache8", "--redis", REDIS, "--key-prefix", keyPrefix, "--out", temp.resolve("out").toString(), *extra)

    /** What an alpha.2 deployment holds: documents with a SHA-1 parent, revocations, the control plane's records. */
    private fun alphaDeployment() {
        val snapshot = CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME
        cocache6(key(snapshot, "platform"), PLATFORM)
        cocache6(key(snapshot, "app:order"), APP)
        cocache6(key(snapshot, "tenant:acme"), TENANT)
        cocache6(key(snapshot, "tenant:ghost"), "", state = CoCache6Format.STATE_MISSING)
        cocache6(key(CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, "jti-1"), "true", ttlMillis = TEN_MINUTES)
        cocache6(key(CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, "jti-absent"), "", state = CoCache6Format.STATE_MISSING)
        cocache6(
            key(CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME, "4:acme:alice"),
            "$ISSUED_BEFORE",
            ttlMillis = TEN_MINUTES
        )
        cocache6(key(CoSecCacheSpecs.REVISION_RECORD_CACHE_NAME, "platform:$SHA1"), PLATFORM)
        cocache6(key(CoSecCacheSpecs.REVISION_INDEX_CACHE_NAME, "platform"), "{}")
        cocache6(key(CoSecCacheSpecs.CONTROL_LEASE_CACHE_NAME, "shadow"), "holder")
        cocache6(key(CoSecCacheSpecs.CONTROL_LIST_CACHE_NAME, "documents"), "{}")
        cocache6(key(CoSecCacheSpecs.CHANGE_REQUEST_CACHE_NAME, "c1"), "{}")
        cocache6(key(CoSecCacheSpecs.SHADOW_REPORT_CACHE_NAME, "w1:i1"), "{}")
        cocache6(key(CoSecCacheSpecs.MEMBERSHIP_CACHE_NAME, "4:acme:alice"), "{}")
        // API keys are kept by a Spring template, not CoCache: not this command's.
        redis.sync().set("$keyPrefix:api-key:abc".encodeToByteArray(), "{\"plain\":true}".encodeToByteArray())
    }

    private fun <T> cocache8Store(block: (CoCacheFactory, CoCacheControlStore) -> T): T =
        RedisBackend.standalone(RedisURI.create(REDIS)).use { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val factory = CoCacheFactory(runtime)
                val heads = factory.createPolicySnapshotCache(CoSecCacheSpecs.policySnapshot(keyPrefix))
                block(factory, CoCacheControlStore.create(factory, heads, keyPrefix))
            }
        }

    @Test
    fun `an alpha deployment moves to CoCache 8, a revoked token staying revoked`() {
        alphaDeployment()
        val (code, output) = cocache8()
        code.assert().describedAs(output).isEqualTo(MigrateMain.OK)
        cocache8Store { factory, store ->
            runBlocking {
                val revisions = Revisions(store)
                listOf(SnapshotKey.Platform, SnapshotKey.App("order"), SnapshotKey.Tenant("acme")).forEach { key ->
                    val head = checkNotNull(revisions.head(key)) { "$key was not published." }
                    head.document.revision.assert().isEqualTo(1)
                    head.document.provenance!!.kind.assert().isEqualTo(ProvenanceKind.MIGRATE)
                    head.document.provenance!!.parent.assert().isNull()
                }
                revisions.head(SnapshotKey.Tenant("ghost")).assert().isNull()
            }
            factory.createLocked(CoSecCacheSpecs.revokedToken(keyPrefix)).get("jti-1").assert().isTrue()
            factory.createLocked(CoSecCacheSpecs.revokedSubject(keyPrefix)).get("4:acme:alice")
                .assert().isEqualTo(ISSUED_BEFORE)
        }
        // The revocations keep what remained of their lifetimes.
        redis.sync().pttl(key(CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, "jti-1").encodeToByteArray())
            .assert().isBetween(1, TEN_MINUTES)
        // What CoCache 6 wrote elsewhere is gone; what publish wrote is CoCache 8's.
        listOf(
            key(CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, "jti-absent"),
            key(CoSecCacheSpecs.REVISION_RECORD_CACHE_NAME, "platform:$SHA1"),
            key(CoSecCacheSpecs.CONTROL_LEASE_CACHE_NAME, "shadow"),
            key(CoSecCacheSpecs.CHANGE_REQUEST_CACHE_NAME, "c1"),
            key(CoSecCacheSpecs.SHADOW_REPORT_CACHE_NAME, "w1:i1"),
            key(CoSecCacheSpecs.MEMBERSHIP_CACHE_NAME, "4:acme:alice"),
            key(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME, "tenant:ghost"),
        ).forEach { raw(it).assert().describedAs(it).isNull() }
        listOf(
            key(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME, "platform"),
            key(CoSecCacheSpecs.REVISION_INDEX_CACHE_NAME, "platform"),
        ).forEach { CoCache8Upgrade.isCoCache6(checkNotNull(raw(it))).assert().describedAs(it).isFalse() }
        raw("$keyPrefix:api-key:abc")!!.decodeToString().assert().isEqualTo("{\"plain\":true}")
        // The export is publish's input, to finish a run that stops after the deletions.
        ConvertedDirectory.read(temp.resolve("out")).keys
            .assert().containsExactlyInAnyOrder(
                SnapshotKey.Platform,
                SnapshotKey.App("order"),
                SnapshotKey.Tenant("acme")
            )
        // A second run finds nothing left to move.
        val (again, second) = run(
            "cocache8",
            "--redis",
            REDIS,
            "--key-prefix",
            keyPrefix,
            "--out",
            temp.resolve("again").toString()
        )
        again.assert().describedAs(second).isEqualTo(MigrateMain.OK)
        second.assert().contains("0 document(s), 0 revocation(s)")
    }

    @Test
    fun `a dry run writes nothing`() {
        alphaDeployment()
        val (code, output) = cocache8("--dry-run")
        code.assert().describedAs(output).isEqualTo(MigrateMain.OK)
        output.assert().contains("3 document(s), 2 revocation(s)")
        untouched()
    }

    @Test
    fun `a change in flight is refused unless discarded`() {
        alphaDeployment()
        cocache6(key(CoSecCacheSpecs.STAGE_CACHE_NAME, "tenant:acme"), "{}")
        val (refused, output) = cocache8()
        refused.assert().describedAs(output).isEqualTo(MigrateMain.FAILED)
        output.assert().contains("REFUSED A change is in flight").contains("Nothing was written.")
        untouched()
        cocache8("--discard-pending").first.assert().isEqualTo(MigrateMain.OK)
        raw(key(CoSecCacheSpecs.STAGE_CACHE_NAME, "tenant:acme")).assert().isNull()
    }

    @Test
    fun `a document publish would refuse, or one that does not decode, writes nothing`() {
        alphaDeployment()
        cocache6(key(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME, "platform"), PLATFORM_WITH_BREAK_GLASS)
        cocache8().let { (code, output) ->
            code.assert().describedAs(output).isEqualTo(MigrateMain.FAILED)
            output.assert().contains("REFUSED The documents cannot be published")
        }
        untouched()
        cocache6(key(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME, "platform"), "{\"schema\":2")
        cocache8().let { (code, output) ->
            code.assert().describedAs(output).isEqualTo(MigrateMain.FAILED)
            output.assert().contains("does not decode")
        }
        untouched()
    }

    /** Every CoCache 6 value is where it was: nothing was written, deleted or published. */
    private fun untouched() {
        listOf(
            key(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME, "app:order"),
            key(CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME, "jti-1"),
            key(CoSecCacheSpecs.REVISION_INDEX_CACHE_NAME, "platform"),
            key(CoSecCacheSpecs.MEMBERSHIP_CACHE_NAME, "4:acme:alice"),
        ).forEach {
            CoCache8Upgrade.isCoCache6(
                checkNotNull(raw(it)) { "$it is gone." }
            ).assert().describedAs(it).isTrue()
        }
    }

    private companion object {
        const val REDIS = "redis://localhost:6379"
        const val TEN_MINUTES = 600_000L
        const val SHA1 = "0123456789abcdef0123456789abcdef01234567"
        val ISSUED_BEFORE: Long = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()

        /** As alpha.2 published it: a provenance whose parent is a SHA-1 of CoCache 6. */
        val PLATFORM = """
            {"schema":2,"revision":4,"publishedAt":"2026-10-20T00:00:00Z",
             "provenance":{"parent":{"revision":3,"digest":"$SHA1"},"change":"c1","kind":"PUBLISH",
                           "publishedBy":{"tenant":"(platform)","principal":"alice"}},
             "policies":[{"id":"health","statements":[{"name":"health","effect":"allow","action":"http:GET","resource":"http:path:/health"}]}],
             "bindings":[{"id":"g:health","subject":"*","app":"*","space":"*","policy":"health"}]}
        """.trimIndent()

        val PLATFORM_WITH_BREAK_GLASS = PLATFORM.replace(
            """{"id":"g:health","subject":"*","app":"*","space":"*","policy":"health"}""",
            """{"id":"g:health","subject":"*","app":"*","space":"*","policy":"health"},""" +
                """{"id":"bg","subject":"*","app":"*","space":"*","policy":"health","expiresAt":"2026-12-01T00:00:00Z","breakGlass":true}""",
        )

        val APP = """
            {"schema":2,"revision":2,"publishedAt":"2026-10-20T00:00:00Z",
             "catalog":{"groups":[{"name":"order","permissions":[{"id":"read"}]}]},
             "policies":[{"id":"perm:order/read","statements":[{"name":"read","effect":"allow","action":"http:GET","resource":"http:path:/orders/**"}]}],
             "bindings":[{"id":"r:clerk:read","subject":{"role":"clerk"},"app":"order","space":"*","policy":"perm:order/read"}]}
        """.trimIndent()

        val TENANT = """
            {"schema":2,"revision":7,"publishedAt":"2026-10-20T00:00:00Z",
             "policies":[{"id":"no-export","statements":[{"name":"export","effect":"deny","action":"http:GET","resource":"http:path:/orders/export"}]}],
             "bindings":[{"id":"t:no-export","subject":"*","app":"*","space":"*","policy":"no-export"}]}
        """.trimIndent()
    }
}
