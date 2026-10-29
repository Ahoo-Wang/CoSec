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
        val legacy = """{"id":"k1","principalId":"svc","tenantId":"acme","roles":["billing"],"policies":["p1"],"attributes":{},"expiresAt":null,"enabled":true}"""
        redis.set("$apiKeyPrefix:h1", legacy)
        redis.set(
            "$apiKeyPrefix:h2",
            """{"id":"k2","principalId":"bob","roles":[],"policies":[],"attributes":{},"enabled":true}"""
        )

        val (dry, dryOutput) = apiKeys(temp.resolve("dry"), "--dry-run")
        dry.assert().describedAs(dryOutput).isEqualTo(MigrateMain.OK)
        dryOutput.assert().contains("No key was written").contains("[k2] has no tenant")
        redis.get("$apiKeyPrefix:h1").assert().isEqualTo(legacy)
        // No document is published, so the attachment never applied: dropped and reported.
        report(temp.resolve("dry")).get("keys").first().get("droppedAttachments").size().assert().isEqualTo(1)

        val (converted, output) = apiKeys(temp.resolve("run"))
        converted.assert().describedAs(output).isEqualTo(MigrateMain.OK)
        val k1 = ApiKeyCodec.decode(redis.get("$apiKeyPrefix:h1"))
        k1.roles.assert().containsExactly("billing")
        k1.tenantId.assert().isEqualTo("acme")
        ApiKeyCodec.decode(redis.get("$apiKeyPrefix:h2")).roles.assert().isEmpty()

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
        val (code, output) = apiKeys(temp.resolve("out"))
        code.assert().describedAs(output).isEqualTo(MigrateMain.FAILED)
        output.assert().contains("has attributes").contains("is not an API key")
        report(temp.resolve("out")).get("accepted").booleanValue().assert().isFalse()
        redis.get("$apiKeyPrefix:h1").assert().isEqualTo(legacy)
    }

    /** Attachability comes from the published documents: the tenant's own policy, else the platform's. */
    @Test
    fun `attachability is read from the published documents`() {
        val store = InMemoryControlStore()
        fun document(kind: DocumentKind, owner: String, policies: String) = PolicyDocumentCodec.decode(
            kind,
            owner,
            """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z","policies":[$policies],"bindings":[]}""".encodeToByteArray(),
        )
        val allow = """"statements":[{"effect":"allow","action":"http:GET"}]"""
        DocumentPublisher(store).publish(
            mapOf(
                SnapshotKey.Platform to document(
                    DocumentKind.PLATFORM,
                    "",
                    """{"id":"shared","attachable":true,$allow},{"id":"both","attachable":true,$allow}""",
                ),
                SnapshotKey.Tenant("acme") to document(
                    DocumentKind.TENANT,
                    "acme",
                    """{"id":"own","attachable":true,$allow},{"id":"fixed","attachable":false,$allow},{"id":"both","attachable":true,$allow}""",
                ),
            ),
        )
        val attachability = StoredAttachability(store)
        val acme = TenantId("acme")
        attachability.of(acme, PolicyId("own")).assert().isEqualTo(Attachability.Attachable.YES)
        attachability.of(acme, PolicyId("shared")).assert().isEqualTo(Attachability.Attachable.YES)
        attachability.of(acme, PolicyId("fixed")).assert().isEqualTo(Attachability.Attachable.NO)
        attachability.of(acme, PolicyId("missing")).assert().isEqualTo(Attachability.Attachable.NO)
        attachability.of(acme, PolicyId("both")).assert().isEqualTo(Attachability.Attachable.AMBIGUOUS)
        attachability.of(TenantId("other"), PolicyId("own")).assert().isEqualTo(Attachability.Attachable.NO)
    }
}
