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

package me.ahoo.cosec.migrate

import io.lettuce.core.RedisURI
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.control.CoCacheControlStore
import me.ahoo.cosec.cocache.snapshot.createPolicySnapshotCache
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * `publish --file`: one document written by hand, as the document `--document` names, with the checks `--in` makes:
 * strict decoding, compiling, no break-glass, written only where no document is yet. Against real Redis.
 */
class PublishFileTest {
    @TempDir
    lateinit var temp: Path

    private val keyPrefix = "migrate-test:${UUID.randomUUID()}"

    private fun run(args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use {
            MigrateMain.run(args.split(' ').filter { it.isNotEmpty() }, it)
        }
        return code to out.toString(Charsets.UTF_8)
    }

    private fun file(name: String, json: String): Path = temp.resolve(name).also { Files.writeString(it, json) }

    private fun publish(file: Path, document: String) =
        run("publish --to-redis $REDIS --key-prefix $keyPrefix --file $file --document $document")

    private fun revision(key: SnapshotKey): Long? = controlStore { store -> Revisions(store).head(key)?.revision }

    private fun <T> controlStore(block: suspend (CoCacheControlStore) -> T): T =
        RedisBackend.standalone(RedisURI.create(REDIS)).use { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val factory = CoCacheFactory(runtime)
                val heads = factory.createPolicySnapshotCache(CoSecCacheSpecs.policySnapshot(keyPrefix))
                runBlocking { block(CoCacheControlStore.create(factory, heads, keyPrefix)) }
            }
        }

    /** The quick start's own document publishes as written, with no report.json beside it. */
    @Test
    fun `the quick start's platform document publishes as written, once`() {
        val platform = Path.of(
            System.getProperty("user.dir")
        ).resolve("../../wiki/examples/quick-start/documents/platform.json")
        val (code, output) = publish(platform, "platform")
        code.assert().describedAs(output).isEqualTo(MigrateMain.OK)
        output.assert().contains("Published platform")
        revision(SnapshotKey.Platform).assert().isEqualTo(1L)

        val (again, refused) = publish(platform, "platform")
        again.assert().describedAs(refused).isEqualTo(MigrateMain.FAILED)
        refused.assert().contains("already exist").contains("Nothing was written")
        revision(SnapshotKey.Platform).assert().isEqualTo(1L)
    }

    @Test
    fun `a tenant document waits for the platform's, then publishes against it`() {
        val tenant = file("acme.json", """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z"}""")
        val (early, refused) = publish(tenant, "tenant:acme")
        early.assert().describedAs(refused).isEqualTo(MigrateMain.FAILED)
        refused.assert().contains("No platform document is published yet")
        revision(SnapshotKey.Tenant("acme")).assert().isNull()

        publish(file("platform.json", """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z"}"""), "platform")
            .first.assert().isEqualTo(MigrateMain.OK)
        val (code, output) = publish(tenant, "tenant:acme")
        code.assert().describedAs(output).isEqualTo(MigrateMain.OK)
        output.assert().contains("Published tenant:acme")
    }

    /**
     * An app or tenant document compiles at publish as every instance compiles it: against the published platform
     * document's condition limits and the external attributes it declares.
     */
    @Test
    fun `a tenant document compiles against the published platform, as every instance compiles it`() {
        val platform = file(
            "platform.json",
            """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","limits":{"maxNodes":4},""" +
                """"attributes":[{"path":"ext.geo.region","type":"string"}]}""",
        )
        publish(platform, "platform").first.assert().isEqualTo(MigrateMain.OK)
        val reads = file(
            "reads.json",
            """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","policies":[{"id":"cn","statements":[""" +
                """{"effect":"allow","action":"http:GET","condition":{"eq":["ext.geo.region","cn"]}}]}]}""",
        )
        val (code, output) = publish(reads, "tenant:acme")
        code.assert().describedAs(output).isEqualTo(MigrateMain.OK)

        val large = file(
            "large.json",
            """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","policies":[{"id":"big","statements":[""" +
                """{"effect":"allow","action":"http:GET","condition":{"and":[{"eq":["subject.id","a"]},""" +
                """{"eq":["subject.id","b"]},{"eq":["subject.id","c"]},{"eq":["subject.id","d"]}]}}]}]}""",
        )
        val (refused, message) = publish(large, "tenant:globex")
        refused.assert().describedAs(message).isEqualTo(MigrateMain.FAILED)
        message.assert().contains("does not compile").contains("has more than 4 nodes")
        revision(SnapshotKey.Tenant("globex")).assert().isNull()
    }

    @Test
    fun `a document the strict reader or the checks refuse writes nothing`() {
        val unknown =
            file("odd.json", """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","polices":[]}""")
        publish(unknown, "platform").first.assert().isEqualTo(MigrateMain.FAILED)
        val glass = file(
            "glass.json",
            """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","bindings":[{"id":"g",""" +
                """"subject":{"principal":"rita","tenant":"(platform)"},"app":"*","space":"*","policy":"cosec:root",""" +
                """"breakGlass":true,"expiresAt":"2026-11-01T01:00:00Z"}]}""",
        )
        val (code, output) = publish(glass, "platform")
        code.assert().describedAs(output).isEqualTo(MigrateMain.FAILED)
        output.assert().contains("writes no break-glass binding")
        revision(SnapshotKey.Platform).assert().isNull()
    }

    /** Refused before anything is read: no Redis is reached (the URI names none). */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "publish --to-redis redis://127.0.0.1:1 --file f.json --document platform --in x",
            "publish --to-redis redis://127.0.0.1:1 --file f.json --document platform --resume",
            "publish --to-redis redis://127.0.0.1:1 --in x --document platform",
            "publish --to-redis redis://127.0.0.1:1 --file f.json",
            "publish --to-redis redis://127.0.0.1:1 --file f.json --document acme",
            "publish --to-redis redis://127.0.0.1:1 --file f.json --document app:",
            "publish --to-redis redis://127.0.0.1:1 --file f.json --document tenant:a:b",
        ],
    )
    fun `a --file that cannot be one is a usage error`(args: String) {
        val (code, output) = run(args)
        code.assert().describedAs(output).isEqualTo(MigrateMain.USAGE)
        output.assert().contains("Usage:")
    }

    private companion object {
        const val REDIS = "redis://localhost:6379"
    }
}
