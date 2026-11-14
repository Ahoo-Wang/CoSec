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
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.ApiKeyCodec
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.PublishOutcome
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.migrate.Fixtures7x.policy
import me.ahoo.cosec.migrate.Fixtures7x.source
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.document.DocumentCompileException
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * A migration that cannot be finished again, or that finishes without what it was asked for, is worse than one that
 * stops (audit issues #1220, #1221, #1225, #1244, #1264). The Redis tests need Redis on `localhost:6379`.
 */
class MigrationRecoveryTest {
    @TempDir
    lateinit var temp: Path

    private companion object {
        const val REDIS = "redis://localhost:6379"
        const val LEGACY =
            """{"id":"k1","principalId":"svc","tenantId":"acme","roles":["reader"],"policies":[],"attributes":{},"expiresAt":null,"enabled":true}"""
    }

    private val now = Instant.parse("2026-11-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val prefix = "migrate-test:${UUID.randomUUID()}"
    private val apiKeyPrefix = "$prefix:api-key"
    private val client = RedisClient.create(REDIS)
    private val redis: RedisCommands<String, String> by lazy { client.connect().sync() }

    @AfterEach
    fun close() {
        redis.keys("$prefix:*").takeIf { it.isNotEmpty() }?.let { redis.del(*it.toTypedArray()) }
        client.shutdown()
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { MigrateMain.run(args.toList(), it) }
        return code to out.toString(Charsets.UTF_8)
    }

    private fun apiKeys(out: Path, vararg more: String) =
        run("api-keys", "--redis", REDIS, "--key-prefix", prefix, "--out", out.toString(), *more)

    // ---- #1220 ----

    @Test
    fun `an output that cannot be written stops api-keys before any key is rewritten`() {
        redis.set("$apiKeyPrefix:h1", LEGACY)
        val notADirectory = Files.writeString(temp.resolve("file"), "not a directory")

        val (code, output) = apiKeys(notADirectory)
        code.assert().describedAs(output).isEqualTo(MigrateMain.FAILED)
        redis.get("$apiKeyPrefix:h1").assert().isEqualTo(LEGACY)

        // The report path itself cannot be written either.
        val out = Files.createDirectories(temp.resolve("out"))
        Files.createDirectories(out.resolve(MigrateMain.API_KEYS_REPORT_FILE))
        val (blocked, blockedOutput) = apiKeys(out)
        blocked.assert().describedAs(blockedOutput).isEqualTo(MigrateMain.FAILED)
        redis.get("$apiKeyPrefix:h1").assert().isEqualTo(LEGACY)
    }

    @Test
    fun `a report that holds a run's plan is never overwritten by a later run`() {
        redis.set("$apiKeyPrefix:h1", LEGACY)
        val out = temp.resolve("out")
        val (first, firstOutput) = apiKeys(out)
        first.assert().describedAs(firstOutput).isEqualTo(MigrateMain.OK)
        val report = out.resolve(MigrateMain.API_KEYS_REPORT_FILE)
        val written = Files.readAllBytes(report)
        CoSecJson.mapper.readTree(
            written
        ).get("memberships").values().single().get("roles").values().map { it.asString() }
            .assert().containsExactly("reader")

        redis.set("$apiKeyPrefix:h2", LEGACY.replace("k1", "k2"))
        val (second, secondOutput) = apiKeys(out)
        second.assert().describedAs(secondOutput).isEqualTo(MigrateMain.FAILED)
        secondOutput.assert().contains(report.toString())
        Files.readAllBytes(report).assert().isEqualTo(written)
        ApiKeyCodec.decode(redis.get("$apiKeyPrefix:h1")).principalId.assert().isEqualTo("apikey:k1")
        redis.get("$apiKeyPrefix:h2").assert().isEqualTo(LEGACY.replace("k1", "k2"))
    }

    @Test
    fun `the report is on disk before any key is rewritten, and a dry run's report may be replaced by the run`() {
        redis.set("$apiKeyPrefix:h1", LEGACY)
        val out = temp.resolve("out")
        val (dry, dryOutput) = apiKeys(out, "--dry-run")
        dry.assert().describedAs(dryOutput).isEqualTo(MigrateMain.OK)
        redis.get("$apiKeyPrefix:h1").assert().isEqualTo(LEGACY)

        val (run, runOutput) = apiKeys(out)
        run.assert().describedAs(runOutput).isEqualTo(MigrateMain.OK)
        val report = CoSecJson.mapper.readTree(Files.readAllBytes(out.resolve(MigrateMain.API_KEYS_REPORT_FILE)))
        report.get("dryRun").asBoolean().assert().isFalse()
        report.get("memberships").size().assert().isEqualTo(1)
        ApiKeyCodec.decode(redis.get("$apiKeyPrefix:h1")).principalId.assert().isEqualTo("apikey:k1")
    }

    // ---- #1244 ----

    @Test
    fun `a key SCAN returns twice is read and planned once`() {
        val commands = mockk<RedisCommands<String, String>>()
        every { commands.get("p:api-key:h1") } returns LEGACY
        every { commands.get("p:api-key:h2") } returns LEGACY.replace("k1", "k2")

        val once = StoredApiKeys.read(commands, "p:api-key") { sequenceOf("p:api-key:h1") }
        val twice = StoredApiKeys.read(commands, "p:api-key") {
            sequenceOf("p:api-key:h1", "p:api-key:h2", "p:api-key:h1", "p:api-key:h2")
        }
        once.legacy.map { it.hash }.assert().containsExactly("h1")
        twice.legacy.map { it.hash }.assert().containsExactly("h1", "h2")

        // Two different Redis keys that hold one tenant/id are still a conflict.
        every { commands.get("p:api-key:h3") } returns LEGACY
        val conflict = StoredApiKeys.read(commands, "p:api-key") { sequenceOf("p:api-key:h1", "p:api-key:h3") }
        conflict.legacy.size.assert().isEqualTo(2)
    }

    // ---- #1225 ----

    @Test
    fun `publish with --dry-run is a usage error and publishes nothing`() {
        val conversion = Converter().convert(source(listOf("p" to policy("p"))), ConvertOptions(publishedAt = now))
        val directory = temp.resolve("converted")
        ConvertedDirectory.write(conversion, directory)

        val (code, output) = run(
            "publish",
            "--to-redis",
            REDIS,
            "--key-prefix",
            prefix,
            "--in",
            directory.toString(),
            "--dry-run"
        )
        code.assert().describedAs(output).isEqualTo(MigrateMain.USAGE)
        output.assert().contains("--dry-run")
        redis.keys("$prefix:*").assert().isEmpty()
    }

    // ---- #1221 ----

    private fun plan(tenant: String) = PartitionPlan.of(
        Converter().convert(
            source(listOf("c" to policy("c", tenantId = tenant))),
            ConvertOptions(Partition.PLATFORM, publishedAt = now)
        ),
        Converter().convert(
            source(listOf("c" to policy("c", tenantId = tenant))),
            ConvertOptions(Partition.BY_OWNER, publishedAt = now)
        ),
    )

    @Test
    fun `a tenant id with a slash is planned under an encoded file name`() {
        val plan = plan("acme/eu")
        plan.keys.assert().containsExactly(SnapshotKey.Tenant("acme/eu"))
        val files = PartitionPlan.write(plan, temp.resolve("plan"))
        files.map { it.fileName.toString() }.assert().containsExactly("tenant-acme%2Feu.json")
        val decoded = PolicyDocumentCodec.decode(DocumentKind.TENANT, "acme/eu", Files.readAllBytes(files.single()))
        decoded.policies.single().id.value.assert().isEqualTo("c")
    }

    @Test
    fun `a plan is never written over an existing file, and names that could collide write nothing`() {
        val plan = plan("acme")
        val directory = temp.resolve("plan")
        PartitionPlan.write(plan, directory)
        assertThrownBy<FileAlreadyExistsException> { PartitionPlan.write(plan, directory) }

        val document = plan.values.single()
        val collide =
            mapOf<SnapshotKey, PolicyDocument>(
                SnapshotKey.Tenant("Acme") to document,
                SnapshotKey.Tenant("acme") to document
            )
        val fresh = temp.resolve("fresh")
        assertThrownBy<IllegalArgumentException> { PartitionPlan.write(collide, fresh) }
        (Files.notExists(fresh) || Files.list(fresh).use { it.count() } == 0L).assert().isTrue()
    }

    @Test
    fun `partition plan on the command line names each tenant it planned`() {
        val export = Files.createDirectories(temp.resolve("export"))
        Files.writeString(export.resolve(ExportDirectory.POLICIES_FILE), "[${policy("c", tenantId = "acme/eu")}]")
        Files.writeString(export.resolve(ExportDirectory.GLOBAL_POLICIES_FILE), "[]")
        Files.writeString(export.resolve(ExportDirectory.APP_ROLE_PERMISSIONS_FILE), "[]")
        val out = temp.resolve("plan")

        val (code, output) = run("partition", "plan", "--from-files", export.toString(), "--out", out.toString())
        code.assert().describedAs(output).isEqualTo(MigrateMain.OK)
        output.assert().contains("acme/eu").contains("tenant-acme%2Feu.json")
        Files.isRegularFile(out.resolve("tenant-acme%2Feu.json")).assert().isTrue()
    }

    // ---- #1264 ----

    @Test
    fun `bootstrap recovery compiles with the attributes the platform declares`() = runBlocking<Unit> {
        val store = InMemoryControlStore(clock)
        val revisions = Revisions(store, clock)
        val publication = Publication("seed", ProvenanceKind.BOOTSTRAP, DocumentPublisher.MIGRATE_ACTOR)
        fun document(kind: DocumentKind, owner: String, body: String): PolicyDocument = PolicyDocumentCodec.decode(
            kind,
            owner,
            """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z"$body}""".encodeToByteArray(),
        )
        val platform =
            document(DocumentKind.PLATFORM, "", ""","attributes":[{"path":"ext.geo.region","type":"string"}]""")
        (revisions.publish(SnapshotKey.Platform, null, platform, publication) as PublishOutcome.Published).revision
        Bootstrap(store, clock).run(PrincipalId("alice"))
        val head = revisions.head(Bootstrap.KEY)!!
        val audit = document(
            DocumentKind.TENANT,
            TenantId.PLATFORM.value,
            ""","policies":[{"id":"audit-policy","statements":[{"effect":"allow","action":"order:read",""" +
                """"condition":{"eq":["ext.geo.region","eu"]}}]}]""",
        )
        val withPolicy = PolicyDocument(
            DocumentKind.TENANT,
            TenantId.PLATFORM.value,
            head.document.revision + 1,
            now,
            audit.policies,
            head.document.bindings,
        )
        revisions.publish(
            Bootstrap.KEY,
            head,
            withPolicy,
            publication
        ).assert().isInstanceOf(PublishOutcome.Published::class.java)

        Bootstrap(store, clock).run(PrincipalId("bob"), force = true).assert().isEqualTo(3)

        val recovered = revisions.head(Bootstrap.KEY)!!.document
        recovered.policies.map { it.id.value }.assert().containsExactly("audit-policy")
        recovered.bindings.map { it.id.value }.assert().containsExactly("platform-admin-alice", "platform-admin-bob")
    }

    @Test
    fun `bootstrap recovery still refuses a policy the platform does not declare`() = runBlocking<Unit> {
        val store = InMemoryControlStore(clock)
        val revisions = Revisions(store, clock)
        val publication = Publication("seed", ProvenanceKind.BOOTSTRAP, DocumentPublisher.MIGRATE_ACTOR)
        Bootstrap(store, clock).run(PrincipalId("alice"))
        val head = revisions.head(Bootstrap.KEY)!!
        val undeclared = PolicyDocumentCodec.decode(
            DocumentKind.TENANT,
            TenantId.PLATFORM.value,
            (
                """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","policies":[{"id":"p","statements":""" +
                    """[{"effect":"allow","action":"order:read","condition":{"eq":["ext.geo.region","eu"]}}]}]}"""
                ).encodeToByteArray(),
        )
        val candidate =
            PolicyDocument(
                DocumentKind.TENANT,
                TenantId.PLATFORM.value,
                2,
                now,
                undeclared.policies,
                head.document.bindings
            )
        revisions.publish(
            Bootstrap.KEY,
            head,
            candidate,
            publication
        ).assert().isInstanceOf(PublishOutcome.Published::class.java)

        assertThrownBy<DocumentCompileException> { Bootstrap(store, clock).run(PrincipalId("bob"), force = true) }
        revisions.head(Bootstrap.KEY)!!.document.revision.assert().isEqualTo(2)
    }
}
