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

package me.ahoo.cosec.migrate

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.CoSecCacheSpecs.storeOfRecord
import me.ahoo.cosec.cache.snapshot.RedisSnapshotDocumentStore
import me.ahoo.cosec.cache.snapshot.createPolicySnapshotCache
import me.ahoo.cosec.migrate.Fixtures7x.appPermission
import me.ahoo.cosec.migrate.Fixtures7x.permission
import me.ahoo.cosec.migrate.Fixtures7x.policy
import me.ahoo.cosec.migrate.Fixtures7x.statement
import me.ahoo.cosec.migrate.v7.cache.GlobalPolicyIndexCache
import me.ahoo.cosec.migrate.v7.cache.V7CacheSpecs
import me.ahoo.cosec.migrate.v7.cache.createGlobalPolicyIndex
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.json.CoSecJsonSerializer
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * `convert --from-redis` and `publish --to-redis` against real Redis (`localhost:6379`), as an operator runs them:
 * 7.x data written in 7.0's layout, converted, published as 8.0 documents beside it, and never overwritten.
 */
class RedisMigrationTest {
    @TempDir
    lateinit var temp: Path

    private val keyPrefix = "migrate-test:${UUID.randomUUID()}"
    private val backend = RedisBackend.standalone(RedisURI.create(REDIS))
    private val runtime = CacheRuntime.builder().backend(backend).build()

    @AfterEach
    fun close() {
        runtime.close()
        backend.close()
    }

    private object RawCodec : Codec<ByteArray> {
        override fun encode(value: ByteArray): ByteArray = value

        override fun decode(bytes: ByteArray): ByteArray = bytes
    }

    /** Writes 7.x values as stored bytes, in 7.0's layout. */
    private val raws = HashMap<String, Cache<String, ByteArray>>()

    private fun raw(cacheName: String): Cache<String, ByteArray> = raws.getOrPut(cacheName) { create(cacheName) }

    private fun create(cacheName: String): Cache<String, ByteArray> = runtime.create(
        CacheSpec.builder<String, ByteArray>("Write$cacheName")
            .namespace(V7CacheSpecs.cacheNamespace(keyPrefix, cacheName))
            .keyPrefix("")
            .keyEncoder { it }
            .codec(RawCodec)
            .policy(CachePolicy.DEFAULT.storeOfRecord())
            .build()
    )

    private fun marker(value: String) {
        val client = RedisClient.create(REDIS)
        try {
            client.connect().use { it.sync().set("$keyPrefix:meta:schema", value) }
        } finally {
            client.shutdown()
        }
    }

    private fun write7x(marker: String = "7") {
        marker(marker)
        val policies = raw(V7CacheSpecs.POLICY_CACHE_NAME)
        policies.put(
            "g",
            policy(
                "g",
                "global",
                listOf(
                    statement("\"/health\""),
                    statement("\"/admin/**\"", "deny"),
                    statement("\"/me/**\"", condition = """{"authenticated":{}}"""),
                    statement(
                        "\"/orders/**\"",
                        "deny",
                        condition = """{"eq":{"part":"context.principal.id","value":"mallory"}}"""
                    ),
                ),
            ).encodeToByteArray()
        )
        policies.put(
            "c",
            policy(
                "c",
                statements = listOf(statement("""{"path":{"pattern":"/reports/**","method":"GET"}}"""))
            ).encodeToByteArray()
        )
        // The policy history is not policy data.
        policies.put("history:c:1", "{}".encodeToByteArray())
        runtime.createGlobalPolicyIndex(
            V7CacheSpecs.globalPolicyIndex(keyPrefix)
        ).add(GlobalPolicyIndexCache.CACHE_KEY, setOf("g"))
        val audit = """{"bool":{"and":[{"path":{"part":"request.path","pattern":"/audit/**"}},{"inRole":{"value":"auditor"}}]}}"""
        val expired = """{"time":{"notAfter":"2020-01-01T00:00:00Z"}}"""
        raw(V7CacheSpecs.APP_PERMISSION_CACHE_NAME).put(
            "order",
            appPermission(
                "order",
                permission("read", "\"/orders/**\""),
                permission("audit", condition = audit),
                permission("expired", "\"/expired/**\"", condition = expired),
                condition = """{"authenticated":{}}""",
            ).encodeToByteArray(),
        )
        val grants = raw(V7CacheSpecs.ROLE_PERMISSION_CACHE_NAME)
        grants.put("order:clerk", """["read","audit","expired"]""".encodeToByteArray())
        grants.put("order:admin@team", """["*"]""".encodeToByteArray())
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { MigrateMain.run(args.toList(), it) }
        return code to out.toString(Charsets.UTF_8)
    }

    @Test
    fun `7x data in Redis converts, publishes beside the 7x keys, and is never overwritten`() {
        write7x()
        val out = temp.resolve("converted")
        val (converted, convertOutput) = run(
            "convert",
            "--from-redis",
            REDIS,
            "--key-prefix",
            keyPrefix,
            "--out",
            out.toString()
        )
        converted.assert().describedAs(convertOutput).isEqualTo(MigrateMain.OK)
        convertOutput.assert().contains("Converted.")
        val report = CoSecJsonSerializer.readTree(Files.readAllBytes(out.resolve(ConvertedDirectory.REPORT_FILE)))
        report.get("counts").get("policies").asInt().assert().isEqualTo(2)
        report.get("counts").get("grants").asInt().assert().isEqualTo(2)

        val (published, publishOutput) = run(
            "publish",
            "--to-redis",
            REDIS,
            "--key-prefix",
            keyPrefix,
            "--in",
            out.toString()
        )
        published.assert().describedAs(publishOutput).isEqualTo(MigrateMain.OK)
        val cache = runtime.createPolicySnapshotCache(CoSecCacheSpecs.policySnapshot(keyPrefix))
        val compiler = PolicyDocumentCompiler()
        listOf(SnapshotKey.Platform, SnapshotKey.App("order")).forEach { key ->
            val document = cache[key]
            document.assert().describedAs("$key").isNotNull()
            compiler.compile(key, document!!)
        }
        // The 7.x data stays: rolling back is not switching.
        raw(V7CacheSpecs.POLICY_CACHE_NAME)["g"].assert().isNotNull()

        val (again, againOutput) = run(
            "publish",
            "--to-redis",
            REDIS,
            "--key-prefix",
            keyPrefix,
            "--in",
            out.toString()
        )
        again.assert().isEqualTo(MigrateMain.FAILED)
        againOutput.assert().contains("already exist").contains("Nothing was written")
    }

    /** A principal of tenant acme with [roles] by space, as a token would carry them. */
    private class Principals(private val roles: Map<String, Map<SpaceId, Set<String>>>) : Authenticator {
        override suspend fun authenticate(credentials: Credentials): AuthnResult {
            val token = credentials.token?.removePrefix("Bearer ") ?: return AuthnResult.Anonymous
            val spaces = roles.getValue(token).mapValues { (_, names) -> names.mapTo(LinkedHashSet(), ::RoleId) }
            return AuthnResult.Authenticated(
                AuthenticatedPrincipal(
                    PrincipalId(token),
                    TenantId("acme"),
                    Memberships(Memberships.Source.CLAIMS, spaces, emptyList())
                ),
                "test",
            )
        }
    }

    private fun request(method: String, path: String, token: String?, space: String? = null): HttpFacts {
        val headers = buildMap<String, List<String>> {
            token?.let { put("Authorization", listOf("Bearer $it")) }
            put(CoSecParameters.APP_ID, listOf("order"))
            space?.let { put(CoSecParameters.SPACE_ID, listOf(it)) }
        }
        return HttpFacts(method, path, null, Headers.of(headers), Cookies.EMPTY, "10.0.0.1", HostScope(null))
    }

    @Test
    fun `published documents decide as the 7x data did`() {
        write7x()
        val out = temp.resolve("converted")
        run(
            "convert",
            "--from-redis",
            REDIS,
            "--key-prefix",
            keyPrefix,
            "--out",
            out.toString()
        ).first.assert().isEqualTo(MigrateMain.OK)
        run(
            "publish",
            "--to-redis",
            REDIS,
            "--key-prefix",
            keyPrefix,
            "--in",
            out.toString()
        ).first.assert().isEqualTo(MigrateMain.OK)
        val cache = runtime.createPolicySnapshotCache(CoSecCacheSpecs.policySnapshot(keyPrefix))
        DocumentSnapshots.source(
            RedisSnapshotDocumentStore(cache),
            PolicyDocumentCompiler(),
            SnapshotOptions()
        ).use { source ->
            val pipeline = Pipeline(
                PipelineComponents(
                    Principals(
                        mapOf(
                            "clerk" to mapOf(SpaceId.DEFAULT to setOf("clerk")),
                            "mallory" to mapOf(SpaceId.DEFAULT to setOf("clerk")),
                            "auditor" to mapOf(SpaceId.DEFAULT to setOf("clerk", "auditor")),
                            "admin" to mapOf(SpaceId("team") to setOf("admin")),
                        ),
                    ),
                    SuspendingSnapshotSource(DocumentSnapshotViews(source)),
                )
            )
            fun status(method: String, path: String, token: String?, space: String? = null) =
                runBlocking { pipeline.evaluate(request(method, path, token, space)) }.status

            // The clerk grant, in the default space of app order.
            status("GET", "/orders/1", "clerk").assert().isEqualTo(200)
            // The global ALLOW applies to anyone, the global DENY to everyone.
            status("GET", "/health", null).assert().isEqualTo(200)
            status("GET", "/admin/users", "clerk").assert().isEqualTo(403)
            // The attachable policy is not attached, so it grants nothing.
            status("GET", "/reports/1", "clerk").assert().isEqualTo(403)
            // `*` granted to admin in space team only.
            status("POST", "/orders/1", "admin", "team").assert().isEqualTo(200)
            status("POST", "/orders/1", "admin").assert().isEqualTo(403)
            // Conditions: authenticated, a principal's DENY over a grant, a path condition turned into the resource
            // beside an inRole read in the requested space, and a time window that has closed.
            status("GET", "/me/1", "clerk").assert().isEqualTo(200)
            status("GET", "/me/1", null).assert().isEqualTo(401)
            status("GET", "/orders/1", "mallory").assert().isEqualTo(403)
            status("GET", "/audit/1", "clerk").assert().isEqualTo(403)
            status("GET", "/audit/1", "auditor").assert().isEqualTo(200)
            status("GET", "/expired/1", "clerk").assert().isEqualTo(403)
        }
    }

    @Test
    fun `resume finishes a partial publish and refuses a document that differs`() {
        write7x()
        val out = temp.resolve("converted")
        run(
            "convert",
            "--from-redis",
            REDIS,
            "--key-prefix",
            keyPrefix,
            "--out",
            out.toString()
        ).first.assert().isEqualTo(MigrateMain.OK)
        val documents = ConvertedDirectory.read(out)
        val cache = runtime.createPolicySnapshotCache(CoSecCacheSpecs.policySnapshot(keyPrefix))
        val store = me.ahoo.cosec.cache.control.CoCacheControlStore.create(runtime, cache, keyPrefix)
        // A first run that stopped after the platform document.
        DocumentPublisher(store).publish(mapOf(SnapshotKey.Platform to documents.getValue(SnapshotKey.Platform)))
        val (refused, refusedOutput) = run(
            "publish",
            "--to-redis",
            REDIS,
            "--key-prefix",
            keyPrefix,
            "--in",
            out.toString()
        )
        refused.assert().isEqualTo(MigrateMain.FAILED)
        refusedOutput.assert().contains("--resume")
        val (resumed, resumedOutput) = run(
            "publish",
            "--to-redis",
            REDIS,
            "--key-prefix",
            keyPrefix,
            "--in",
            out.toString(),
            "--resume"
        )
        resumed.assert().describedAs(resumedOutput).isEqualTo(MigrateMain.OK)
        resumedOutput.assert().contains("Already published, identical: platform").contains("Published app:order")
        // Everything is published now: a resume writes nothing.
        DocumentPublisher(store).publish(documents, resume = true).let {
            it.written.assert().isEmpty()
            it.skipped.assert().containsExactly(SnapshotKey.Platform, SnapshotKey.App("order"))
        }
    }

    @Test
    fun `resume refuses a document in Redis that is not the converted one`() {
        write7x()
        val out = temp.resolve("converted")
        run(
            "convert",
            "--from-redis",
            REDIS,
            "--key-prefix",
            keyPrefix,
            "--out",
            out.toString()
        ).first.assert().isEqualTo(MigrateMain.OK)
        run(
            "publish",
            "--to-redis",
            REDIS,
            "--key-prefix",
            keyPrefix,
            "--in",
            out.toString()
        ).first.assert().isEqualTo(MigrateMain.OK)
        val other = temp.resolve("other")
        val newer = Converter().convert(
            Fixtures7x.source(listOf("x" to policy("x"))),
            ConvertOptions(publishedAt = java.time.Instant.parse("2030-01-01T00:00:00Z")),
        )
        ConvertedDirectory.write(newer, other)
        val (differ, differOutput) = run(
            "publish",
            "--to-redis",
            REDIS,
            "--key-prefix",
            keyPrefix,
            "--in",
            other.toString(),
            "--resume"
        )
        differ.assert().isEqualTo(MigrateMain.FAILED)
        differOutput.assert().contains("differ from the converted ones").contains("Nothing was written")
    }

    @Test
    fun `a failure partway reports exactly what was written`() {
        val error = PublishException("Writing app:b failed: down.", listOf(SnapshotKey.Platform))
        error.message.assert().contains("Written before it stopped: [platform]").contains("--resume")
        DocumentsExistException(listOf(SnapshotKey.App("b")), listOf(SnapshotKey.Platform)).written.assert()
            .containsExactly(SnapshotKey.Platform)
    }

    @Test
    fun `data without the 7 marker is rejected`() {
        write7x(marker = "6")
        val out = temp.resolve("rejected")
        val (code, output) = run("convert", "--from-redis", REDIS, "--key-prefix", keyPrefix, "--out", out.toString())
        code.assert().isEqualTo(MigrateMain.FAILED)
        output.assert().contains("REJECT SCHEMA_MARKER $keyPrefix:meta:schema")
        Files.exists(out.resolve(ConvertedDirectory.PLATFORM_FILE)).assert().isFalse()
    }

    @Test
    fun `the reader reads entries as stored and lists them by key`() {
        write7x()
        val keys = CoSec7xReader.Keys(
            setOf("g", "c", "absent"),
            setOf("order"),
            setOf(CoSec7xReader.parseGrantKey("order:admin@team"))
        )
        val source = CoSec7xReader(backend, keyPrefix).use { it.source(keys, null) }
        source.policies.map { it.id }.assert().containsExactly("c", "g")
        source.policies.first().location.assert().isEqualTo("$keyPrefix:policy:c")
        source.globalIndex.assert().containsExactly("g")
        source.apps.single().grants.single().let {
            it.roleId.assert().isEqualTo("admin")
            it.spaceId.assert().isEqualTo("team")
        }
        CoSec7xReader.grantKeyOf(CoSec7xReader.parseGrantKey("order:admin@team")).assert().isEqualTo("admin@team")
        CoSec7xReader.grantKeyOf(CoSec7xReader.parseGrantKey("order:admin")).assert().isEqualTo("admin")
    }

    private companion object {
        const val REDIS = "redis://localhost:6379"
    }
}
