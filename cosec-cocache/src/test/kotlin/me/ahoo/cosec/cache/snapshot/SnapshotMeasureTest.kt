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
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import me.ahoo.cosec.cache.AppSpacedRoleId
import me.ahoo.cosec.parity.appPermissionJson
import me.ahoo.cosec.parity.permissionJson
import me.ahoo.cosec.parity.policyJson
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * RFC 0003 §4.8: `snapshotMeasure` against the local Redis, on synthetic 7.x data.
 */
class SnapshotMeasureTest {
    private val keyPrefix = newKeyPrefix()

    /** Two global policies and a custom one; two apps, one granting in two spaces. */
    private fun store7x() {
        RedisTarget.REDIS.backend().use { backend ->
            Raw7xWriter(backend, keyPrefix).use { writer ->
                writer.policy("secret-global", policyJson("secret-global", "global"))
                writer.policy(
                    "secret-deny",
                    policyJson(
                        "secret-deny",
                        "global",
                        """{"name":"a","effect":"deny","action":"/a/**"},{"name":"b","effect":"deny","action":"/b/**"}""",
                    ),
                )
                writer.policy("secret-custom", policyJson("secret-custom"))
                writer.index("secret-global", "secret-deny")
                writer.appPermission(
                    "secret-app",
                    appPermissionJson(
                        "secret-app",
                        listOf("x", "y", "z").joinToString(",") { permissionJson("secret-$it") }
                    ),
                )
                writer.grants(AppSpacedRoleId("secret-app", "secret-role"), """["secret-x"]""")
                writer.grants(
                    AppSpacedRoleId("secret-app", "secret-role", "secret-space"),
                    """["secret-y","secret-z"]"""
                )
                writer.appPermission("secret-other", appPermissionJson("secret-other", permissionJson("secret-w")))
            }
        }
    }

    private fun requests(dir: Path): Path {
        val file = dir.resolve("requests.jsonl")
        javaClass.getResourceAsStream("/cosec-replay/requests.jsonl")!!.use { Files.copy(it, file) }
        return file
    }

    @Test
    fun measuresWithoutNamingAnything(@TempDir dir: Path) {
        store7x()
        val output = dir.resolve("out/snapshot-measure.json")
        val out = ByteArrayOutputStream()
        val code = SnapshotMeasure.run(
            SnapshotMeasure.Options(
                redisUrl = REDIS_URL,
                keyPrefix = keyPrefix,
                requestsFile = requests(dir),
                load = RevalidationLoad(50, Duration.ofSeconds(10)),
                roundTrips = true,
                output = output,
            ),
            PrintStream(out, true),
        )
        code.assert().isEqualTo(0)
        val text = Files.readString(output)
        val summary = out.toString()
        // Counts, sizes and times only: neither the report nor the summary names a policy, app, role or space.
        text.assert().doesNotContain("secret")
        summary.assert().doesNotContain("secret").contains("Within RFC 0003 Q14's limits")

        val report = CoSecJsonSerializer.readTree(text)
        report.get("inputSha256").asString().assert().hasSize(64)
        val policies = report.get("policies")
        policies.get("policies").asInt().assert().isEqualTo(3)
        policies.get("globalPolicies").asInt().assert().isEqualTo(2)
        policies.get("statements").asInt().assert().isEqualTo(4)
        policies.get("encodedBytes").asInt().assert().isGreaterThanOrEqualTo(policies.get("jsonBytes").asInt())
        policies.get("compileMillis").get("count").asInt().assert().isEqualTo(SnapshotMeasure.COMPILE_RUNS)
        policies.get("residentBytes").asLong().assert().isGreaterThanOrEqualTo(0L)

        val apps = report.get("apps")
        apps.get("count").asInt().assert().isEqualTo(2)
        apps.get("permissions").get("max").asDouble().assert().isEqualTo(3.0)
        apps.get("grantedRoles").get("total").asDouble().assert().isEqualTo(2.0)
        apps.get("spaces").get("max").asDouble().assert().isEqualTo(2.0)

        val revalidation = report.get("revalidation")
        val totalBytes = revalidation.get("totalBytes").asLong()
        totalBytes.assert().isEqualTo(policies.get("jsonBytes").asLong() + apps.get("jsonBytes").get("total").asLong())
        revalidation.get("redisBusyFraction").asDouble().assert()
            .isCloseTo(totalBytes / (1024.0 * 1024.0) * 2.5 * 50 / 10_000, org.assertj.core.data.Offset.offset(1e-12))

        val roundTrips = report.get("roundTrips").get("documents").values().toList()
        // The policies document and both apps, largest first.
        roundTrips.map { it.get("kind").asString() }.assert().containsExactly("POLICIES", "APP", "APP")
        roundTrips.forEach { document ->
            document.get("putMillis").get("count").asInt().assert().isEqualTo(SnapshotMeasure.ROUND_TRIPS)
            document.get("unchangedConditionalReadMillis").get("count").asInt().assert()
                .isEqualTo(SnapshotMeasure.ROUND_TRIPS)
            // The other instance heard of the writes.
            document.get("invalidationsHeard").asInt().assert().isGreaterThan(SnapshotMeasure.ROUND_TRIPS / 2)
        }
        // The round trips wrote under a key prefix of their own, and removed what they wrote.
        scan("$keyPrefix:measure:*").assert().isEmpty()

        val activeApps = report.get("activeApps")
        activeApps.get("distinctApps").asInt().assert().isEqualTo(1)
        activeApps.get("1h").get("max").asDouble().assert().isEqualTo(1.0)
        report.get("verdict").get("withinLimits").asBoolean().assert().isTrue()
    }

    @Test
    fun onlyReadsWithoutRoundTrips(@TempDir dir: Path) {
        store7x()
        val before = scan("$keyPrefix:*").toSet()
        val output = dir.resolve("snapshot-measure.json")
        SnapshotMeasure.run(
            SnapshotMeasure.Options(redisUrl = REDIS_URL, keyPrefix = keyPrefix, output = output),
            PrintStream(ByteArrayOutputStream(), true),
        ).assert().isEqualTo(0)
        scan("$keyPrefix:*").toSet().assert().isEqualTo(before)
        val report = CoSecJsonSerializer.readTree(Files.readString(output))
        report.has("roundTrips").assert().isFalse()
        report.has("activeApps").assert().isFalse()
    }

    @Test
    fun dataWithProblemsIsNotMeasured(@TempDir dir: Path) {
        RedisTarget.REDIS.backend().use { backend ->
            Raw7xWriter(backend, keyPrefix).use { writer ->
                // Indexed as global, without content: 7.0 fails every request; the reader refuses it.
                writer.index("gone")
            }
        }
        val output = dir.resolve("snapshot-measure.json")
        val out = ByteArrayOutputStream()
        SnapshotMeasure.run(
            SnapshotMeasure.Options(redisUrl = REDIS_URL, keyPrefix = keyPrefix, output = output),
            PrintStream(out, true),
        ).assert().isEqualTo(1)
        out.toString().assert().contains("1 problem(s)").doesNotContain("gone")
        Files.exists(output).assert().isFalse()
    }

    @Test
    fun percentilesAreNearestRank() {
        val values = (1..100).map { it.toDouble() }
        SnapshotMeasure.percentile(values, 0.5).assert().isEqualTo(50.0)
        SnapshotMeasure.percentile(values, 0.99).assert().isEqualTo(99.0)
        SnapshotMeasure.percentile(listOf(7.0), 0.99).assert().isEqualTo(7.0)
        SnapshotMeasure.percentile(emptyList(), 0.5).assert().isEqualTo(0.0)
    }

    private fun scan(pattern: String): List<String> {
        val client = RedisClient.create(REDIS_URL)
        try {
            client.connect().use { connection ->
                val commands = connection.sync()
                val keys = ArrayList<String>()
                var cursor = commands.scan(ScanCursor.INITIAL, ScanArgs.Builder.matches(pattern).limit(1000))
                keys += cursor.keys
                while (!cursor.isFinished) {
                    cursor = commands.scan(cursor, ScanArgs.Builder.matches(pattern).limit(1000))
                    keys += cursor.keys
                }
                return keys
            }
        } finally {
            client.shutdown()
        }
    }

    companion object {
        private const val REDIS_URL = "redis://localhost:6379"
    }
}
