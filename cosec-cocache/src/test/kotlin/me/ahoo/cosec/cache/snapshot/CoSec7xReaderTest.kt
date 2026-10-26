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

package me.ahoo.cosec.cache.snapshot

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.cache.AppSpacedRoleId
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.parity.PolicySetParity
import me.ahoo.cosec.parity.appPermissionJson
import me.ahoo.cosec.parity.permissionJson
import me.ahoo.cosec.parity.policyJson
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CoSec7xReaderTest {
    private val backend = InMemoryBackend()
    private val keyPrefix = newKeyPrefix()

    private fun policy(id: String, type: String): String = policyJson(id, type)

    private val app = appPermissionJson("app", permissionJson("p1"))

    /** A condition no instance can decode, as one registered only on a newer writer. */
    private val unknown = """{"noSuchMatcher":{}}"""

    private fun read(keys: CoSec7xReader.Keys): CoSec7xReader.Conversion =
        CoSec7xReader(backend, keyPrefix).use { it.read(keys) }

    private fun keys(policies: Set<String> = emptySet(), apps: Set<String> = emptySet(), grants: Set<AppSpacedRoleId> = emptySet()) =
        CoSec7xReader.Keys(policies, apps, grants)

    private fun Map<SnapshotKey, SnapshotDocument>.content(key: SnapshotKey) = SnapshotContent.read(key, getValue(key))

    @Test
    fun convertsConsistentData() {
        Raw7xWriter(backend, keyPrefix).use { raw ->
            raw.policy("g", policy("g", "global"))
            raw.policy("c", policy("c", "custom"))
            raw.index("g")
            raw.appPermission("app", app)
            raw.grants(AppSpacedRoleId("app", "admin"), """["*"]""")
            raw.grants(AppSpacedRoleId("app", "viewer", "team"), """["p1","ghost"]""")
        }
        val conversion = read(
            keys(
                setOf("g", "c"),
                setOf("app"),
                setOf(AppSpacedRoleId("app", "admin"), AppSpacedRoleId("app", "viewer", "team"))
            )
        )
        conversion.problems.assert().isEmpty()
        // ghost is not defined by the app: evaluation fails on it as in 7.0, so only a warning
        conversion.warnings.single().assert().contains("ghost")
        conversion.inputSha256.length.assert().isEqualTo(64)
        val documents = conversion.documents(revision = 3)
        documents.keys.assert().containsExactlyInAnyOrder(SnapshotKey.Policies, SnapshotKey.App("app"))
        documents.values.forEach { it.revision.assert().isEqualTo(3L) }
        (
            documents.content(
                SnapshotKey.Policies
            ) as SnapshotContent.Policies
            ).policies.keys.assert().containsExactly("c", "g")
        (documents.content(SnapshotKey.App("app")) as SnapshotContent.App).grants.keys
            .assert().containsExactly("admin", "viewer@team")
    }

    @Test
    fun theInputHashCoversEveryValue() {
        Raw7xWriter(backend, keyPrefix).use { raw -> raw.policy("c", policy("c", "custom")) }
        val first = read(keys(setOf("c"))).inputSha256
        Raw7xWriter(backend, keyPrefix).use { raw -> raw.policy("c", policy("c", "system")) }
        read(keys(setOf("c"))).inputSha256.assert().isNotEqualTo(first)
    }

    @Test
    fun reportsAndRefusesWhatADocumentCannotExpress() {
        Raw7xWriter(backend, keyPrefix).use { raw ->
            raw.policy("unindexed", policy("unindexed", "global"))
            raw.policy("renamed", policy("other", "custom"))
            raw.index("missing")
            raw.appPermission("renamed-app", app)
            raw.grants(AppSpacedRoleId("orphan", "r"), """["p1"]""")
            raw.policy("garbage", "{not json")
            raw.policy("array", "[]")
        }
        val conversion = read(
            keys(
                setOf("unindexed", "renamed", "garbage", "array"),
                setOf("renamed-app"),
                setOf(AppSpacedRoleId("orphan", "r"))
            )
        )
        conversion.accepted.assert().isFalse()
        val problems = conversion.problems.joinToString("\n")
        listOf(
            "policy [unindexed] is of type GLOBAL but not in the global policy index",
            "policy [renamed] holds content with id",
            "lists policy [missing], which has no content",
            "app permission [renamed-app] holds content with id",
            "app [orphan] has grants",
            "policy [garbage] is not valid JSON",
            "policy [array] is not a JSON object",
        ).forEach { problems.assert().contains(it) }
        assertThrows<IllegalStateException> { conversion.documents() }
    }

    /**
     * Review requirement: an entry 7.0's caches cannot decode keeps its key's id in the document, so that only that
     * entry fails once compiled (F4) and not the whole document.
     */
    @Test
    fun keepsUndecodableEntriesUnderTheirKeyIds() {
        Raw7xWriter(backend, keyPrefix).use { raw ->
            // no id in the content: the reader gives it the key's
            raw.policy(
                "broken",
                """{"name":"broken","type":"custom","tenantId":"t","condition":$unknown,"statements":[]}"""
            )
            raw.policy("fine", policy("fine", "custom"))
            raw.appPermission("app", app)
            raw.grants(AppSpacedRoleId("app", "r1"), """{"not":"an array"}""")
            raw.grants(AppSpacedRoleId("app", "r2"), """["p1"]""")
        }
        val conversion = read(
            keys(
                setOf("broken", "fine"),
                setOf("app"),
                setOf(AppSpacedRoleId("app", "r1"), AppSpacedRoleId("app", "r2"))
            )
        )
        conversion.problems.assert().isEmpty()
        conversion.warnings.size.assert().isEqualTo(2)
        val documents = conversion.documents()
        val policies = (documents.content(SnapshotKey.Policies) as SnapshotContent.Policies).policies
        SnapshotContent.policyId(policies.getValue("broken")).assert().isEqualTo("broken")

        // compiled: only the requests reading the broken entries fail
        fun layers(policyIds: Set<String>, roles: Set<String>) = PolicySetParity.ofSnapshot(
            EvaluateRequest(path = "/a", headers = mapOf(AppIdCapable.APP_ID_KEY to "app")),
            SimpleSecurityContext(SimplePrincipal("user", policies = policyIds, roles = roles)),
        ) { key -> Result.success(documents[key]) }.associate { (it as List<*>)[0] to it[1] }
        layers(setOf("fine"), setOf("r2")).assert().isEqualTo(
            mapOf(LayerKind.GLOBAL to "loaded", LayerKind.PRINCIPAL to "loaded", LayerKind.ROLE to "loaded")
        )
        layers(setOf("broken"), setOf("r1")).assert().isEqualTo(
            mapOf(LayerKind.GLOBAL to "loaded", LayerKind.PRINCIPAL to "unavailable", LayerKind.ROLE to "unavailable")
        )
    }

    @Test
    fun anUndecodablePolicyMustAgreeWithTheIndex() {
        Raw7xWriter(backend, keyPrefix).use { raw ->
            // 7.0 fails the global layer on it (it is indexed); the document would fail only its readers
            raw.policy("indexed-custom", policyJson("indexed-custom", "custom", condition = unknown))
            // the document would fail the global layer on it (no readable type); 7.0 does not (not indexed)
            raw.policy("untyped", """{"id":"untyped","tenantId":"t","condition":$unknown,"statements":[]}""")
            // agrees: indexed and global
            raw.policy("indexed-global", policyJson("indexed-global", "global", condition = unknown))
            raw.index("indexed-custom", "indexed-global")
        }
        val problems = read(keys(setOf("indexed-custom", "untyped", "indexed-global"))).problems
        problems.size.assert().isEqualTo(2)
        problems.joinToString().assert().contains("[indexed-custom]").contains("[untyped]")
    }

    @Test
    fun unmigratedContentFailsTheConversion() {
        val target = RedisTarget.REDIS
        val prefix = newKeyPrefix()
        target.backend().use { redis ->
            target.rawSet(
                CoSecCacheSpecs.cacheNamespace(prefix, CoSecCacheSpecs.POLICY_CACHE_NAME) + ":p",
                "{}".encodeToByteArray()
            )
            CoSec7xReader(redis, prefix).use { reader ->
                assertThrows<CacheReadException> { reader.read(CoSec7xReader.Keys(setOf("p"), emptySet(), emptySet())) }
            }
        }
    }

    @Test
    fun scansTheKeysOf7xDataButNotTheHistory() {
        val target = RedisTarget.REDIS
        val prefix = newKeyPrefix()
        target.backend().use { redis ->
            Raw7xWriter(redis, prefix).use { raw ->
                raw.policy("p1", policy("p1", "custom"))
                raw.policy("history:p1", "[]")
                raw.appPermission("app", app)
                raw.grants(AppSpacedRoleId("app", "r"), """["p1"]""")
                raw.grants(AppSpacedRoleId("app", "r", "s"), """["p1"]""")
            }
            val client = RedisClient.create(RedisURI.create("redis://localhost:6379"))
            try {
                client.connect().use { connection ->
                    val keys = CoSec7xReader.keys(prefix) { pattern ->
                        sequence {
                            val args = ScanArgs.Builder.matches(pattern).limit(1000)
                            var cursor = connection.sync().scan(ScanCursor.INITIAL, args)
                            yieldAll(cursor.keys)
                            while (!cursor.isFinished) {
                                cursor = connection.sync().scan(cursor, args)
                                yieldAll(cursor.keys)
                            }
                        }
                    }
                    keys.policyIds.assert().containsExactly("p1")
                    keys.appIds.assert().containsExactly("app")
                    keys.grants.assert().containsExactlyInAnyOrder(
                        AppSpacedRoleId("app", "r"),
                        AppSpacedRoleId("app", "r", "s")
                    )
                }
            } finally {
                client.shutdown()
            }
        }
    }

    @Test
    fun parsesGrantKeysAs7xWritesThem() {
        listOf(AppSpacedRoleId("a", "r"), AppSpacedRoleId("a", "r", "s")).forEach {
            CoSec7xReader.parseGrantKey(it.toString()).assert().isEqualTo(it)
        }
        assertThrows<IllegalArgumentException> { CoSec7xReader.parseGrantKey("no-app") }
    }

    @Test
    fun estimatesTheRevalidationLoad() {
        val document = SnapshotDocument(1, 1, java.time.Instant.EPOCH, ByteArray(8 * 1024 * 1024))
        val load = RevalidationLoad(instances = 10, interval = java.time.Duration.ofSeconds(10))
        // 8 MB costs Redis about 20 ms per conditional read; 10 instances every 10 s keep it busy 2 % of the time
        load.passMillis(listOf(document)).assert().isEqualTo(20.0)
        load.busyFraction(listOf(document)).assert().isEqualTo(0.02)
        load.describe(listOf(document)).assert().contains("2.00%")
    }

    /**
     * Review: 7.0 accepts a space containing `@` (`r@a@b`) and never applies such a grant; a document cannot hold it,
     * because the compiler would fail the whole app on it.
     */
    @Test
    fun reportsGrantsKeyedByNoRoleInASpace() {
        Raw7xWriter(backend, keyPrefix).use { raw ->
            raw.appPermission("app", app)
            raw.grants(AppSpacedRoleId("app", "r", "a@b"), """["p1"]""")
        }
        val conversion = read(keys(apps = setOf("app"), grants = setOf(AppSpacedRoleId("app", "r", "a@b"))))
        conversion.problems.single().assert().contains("keyed [r@a@b]")
    }

    @Test
    fun keepsTheStoredBytesOf7xValues() {
        val odd = """{"id":"p","name":"p","type":"custom","tenantId":"t","statements":[],"x":[1e400,1.10]}"""
        Raw7xWriter(backend, keyPrefix).use { raw ->
            raw.policy("p", odd)
            raw.appPermission("app", app)
            raw.grants(AppSpacedRoleId("app", "r"), """[ "p1" ]""")
        }
        val documents = read(keys(setOf("p"), setOf("app"), setOf(AppSpacedRoleId("app", "r")))).documents()
        documents.getValue(SnapshotKey.Policies).bodyBytes().decodeToString().assert().contains(odd)
        documents.getValue(SnapshotKey.App("app")).bodyBytes().decodeToString().assert()
            .contains(app).contains(""""r":[ "p1" ]""")
    }
}
