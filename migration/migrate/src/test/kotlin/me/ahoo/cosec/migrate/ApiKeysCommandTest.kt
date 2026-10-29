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
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.ApiKeyCodec
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** `cosec-migrate api-keys` against real Redis (`localhost:6379`), as an operator runs it (RFC 0009 §4.4). */
class ApiKeysCommandTest {
    @TempDir
    lateinit var temp: Path

    private companion object {
        const val REDIS = "redis://localhost:6379"
    }

    private val prefix = "migrate-test:${UUID.randomUUID()}"
    private val apiKeyPrefix = "$prefix:api-key"
    private val client = RedisClient.create(REDIS)
    private val redis = client.connect().sync()

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

    private fun apiKeys(out: Path, vararg more: String) = run(
        "api-keys", "--redis", REDIS, "--api-key-prefix", apiKeyPrefix, "--key-prefix", prefix,
        "--out", out.toString(), *more,
    )

    private fun report(out: Path) = CoSecJson.mapper.readTree(
        Files.readAllBytes(out.resolve(MigrateMain.API_KEYS_REPORT_FILE))
    )

    @Test
    fun `a dry run writes the report only, a run converts every key once`() {
        val legacy = """{"id":"k1","principalId":"svc","tenantId":"acme","roles":["billing"],"policies":[],"attributes":{},"expiresAt":null,"enabled":true}"""
        redis.set("$apiKeyPrefix:h1", legacy)
        redis.set(
            "$apiKeyPrefix:h2",
            """{"id":"k2","principalId":"bob","roles":[],"policies":[],"attributes":{},"enabled":true}"""
        )
        val memberships = temp.resolve("memberships.json").also {
            Files.writeString(it, """[{"tenant":"acme","principal":"svc","roles":["billing"]}]""")
        }

        val (dry, dryOutput) = apiKeys(temp.resolve("dry"), "--dry-run", "--memberships", memberships.toString())
        dry.assert().describedAs(dryOutput).isEqualTo(MigrateMain.OK)
        dryOutput.assert().contains("No key was written").contains("[k2] has no tenant")
        redis.get("$apiKeyPrefix:h1").assert().isEqualTo(legacy)

        val (converted, output) = apiKeys(temp.resolve("run"), "--memberships", memberships.toString())
        converted.assert().describedAs(output).isEqualTo(MigrateMain.OK)
        // svc holds billing: the key stays with it, capped. bob is not in the export: a principal of its own.
        val k1 = ApiKeyCodec.decode(redis.get("$apiKeyPrefix:h1"))
        k1.principalId.assert().isEqualTo("svc")
        k1.roles.assert().containsExactly("billing")
        val k2 = ApiKeyCodec.decode(redis.get("$apiKeyPrefix:h2"))
        k2.principalId.assert().isEqualTo("apikey:k2")
        k2.tenantId.assert().isEqualTo(TenantId.DEFAULT.value)

        val (again, againOutput) = apiKeys(temp.resolve("again"))
        again.assert().describedAs(againOutput).isEqualTo(MigrateMain.OK)
        report(temp.resolve("again")).get("alreadyConverted").values().map { it.asString() }.toSet().assert()
            .isEqualTo(setOf("h1", "h2"))
    }

    @Test
    fun `a refused key stops the run before anything is written`() {
        val legacy = """{"id":"k1","principalId":"svc","tenantId":"acme","roles":["billing"],"policies":[],"attributes":{"team":"a"},"enabled":true}"""
        redis.set("$apiKeyPrefix:h1", legacy)
        redis.set("$apiKeyPrefix:h2", "not json at all")
        // No document is published, so the attachment never resolved: refused, never dropped.
        redis.set(
            "$apiKeyPrefix:h3",
            """{"id":"k3","principalId":"svc","tenantId":"acme","roles":[],"policies":["p1"],"attributes":{},"enabled":true}""",
        )
        val (code, output) = apiKeys(temp.resolve("out"))
        code.assert().describedAs(output).isEqualTo(MigrateMain.FAILED)
        output.assert().contains("has attributes").contains("is not an API key").contains("does not resolve")
        report(temp.resolve("out")).get("accepted").booleanValue().assert().isFalse()
        redis.get("$apiKeyPrefix:h1").assert().isEqualTo(legacy)
    }

    /** Review M2: a key is rewritten only while it holds what was read, so a revocation in between is never undone. */
    @Test
    fun `a key that changed since it was read is left as it is`() {
        val read = """{"id":"k1","principalId":"svc","tenantId":"acme","roles":[],"policies":[],"attributes":{},"enabled":true}"""
        redis.set("$apiKeyPrefix:h1", read)
        StoredApiKeys.replace(redis, "$apiKeyPrefix:h1", read, "converted").assert().isTrue()
        redis.get("$apiKeyPrefix:h1").assert().isEqualTo("converted")
        redis.set("$apiKeyPrefix:h1", "disabled since")
        StoredApiKeys.replace(redis, "$apiKeyPrefix:h1", read, "converted").assert().isFalse()
        redis.get("$apiKeyPrefix:h1").assert().isEqualTo("disabled since")
        redis.del("$apiKeyPrefix:h1")
        StoredApiKeys.replace(redis, "$apiKeyPrefix:h1", read, "converted").assert().isFalse()
        redis.exists("$apiKeyPrefix:h1").assert().isEqualTo(0L)
    }

    /**
     * Review H2: an attachment resolves as the engine resolves it, the tenant's own policy or the platform's, attachable,
     * and when both define it only with the same content; the principal's own grants and the bound roles are read too.
     */
    @Test
    fun `the published documents are read as the engine reads them`() {
        val store = InMemoryControlStore()
        fun document(
            kind: DocumentKind,
            owner: String,
            policies: String,
            bindings: String = ""
        ) = PolicyDocumentCodec.decode(
            kind,
            owner,
            """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z","policies":[$policies],"bindings":[$bindings]}"""
                .encodeToByteArray(),
        )
        val allow = """"statements":[{"effect":"allow","action":"http:GET"}]"""
        val other = """"statements":[{"effect":"allow","action":"http:POST"}]"""
        DocumentPublisher(store).publish(
            mapOf(
                SnapshotKey.Platform to document(
                    DocumentKind.PLATFORM,
                    "",
                    """{"id":"shared","attachable":true,$allow},{"id":"same","attachable":true,$allow},""" +
                        """{"id":"differs","attachable":true,$allow}""",
                ),
                SnapshotKey.Tenant("acme") to document(
                    DocumentKind.TENANT,
                    "acme",
                    """{"id":"own","attachable":true,$allow},{"id":"fixed","attachable":false,$allow},""" +
                        """{"id":"same","attachable":true,$allow},{"id":"differs","attachable":true,$other}""",
                    """{"id":"p:alice","subject":{"principal":"alice","tenant":"acme"},"app":"*","space":"*","policy":"own"},""" +
                        """{"id":"r:clerk","subject":{"role":"clerk"},"app":"*","space":"*","policy":"own"}""",
                ),
            ),
        )
        val documents = StoredDocuments(store)
        val acme = TenantId("acme")
        documents.attachable(acme, PolicyId("own")).assert().isTrue()
        documents.attachable(acme, PolicyId("shared")).assert().isTrue()
        documents.attachable(acme, PolicyId("same")).assert().isTrue()
        documents.attachable(acme, PolicyId("differs")).assert().isFalse()
        documents.attachable(acme, PolicyId("fixed")).assert().isFalse()
        documents.attachable(acme, PolicyId("missing")).assert().isFalse()
        documents.attachable(TenantId("other"), PolicyId("own")).assert().isFalse()
        documents.principalGrants(acme, "alice").assert().containsExactly("tenant:acme/p:alice")
        documents.principalGrants(acme, "bob").assert().isEmpty()
        documents.boundRole(RoleId("clerk")).assert().isTrue()
        documents.boundRole(RoleId("api-key.k1")).assert().isFalse()
    }
}
