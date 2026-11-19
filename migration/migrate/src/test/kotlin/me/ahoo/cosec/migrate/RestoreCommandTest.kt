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
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** `restore` (UC-6) against real Redis: the way back from a lock-out, printed first, written only when confirmed. */
class RestoreCommandTest {
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

    private fun <T> controlStore(block: suspend (CoCacheControlStore) -> T): T =
        RedisBackend.standalone(RedisURI.create(REDIS)).use { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val factory = CoCacheFactory(runtime)
                val heads = factory.createPolicySnapshotCache(CoSecCacheSpecs.policySnapshot(keyPrefix))
                runBlocking { block(CoCacheControlStore.create(factory, heads, keyPrefix)) }
            }
        }

    private val opened =
        """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","policies":[{"id":"control-api","statements":""" +
            """[{"effect":"allow","action":"http:*","resource":"http:path:/cosec/control/v1/**"}]}],""" +
            """"bindings":[{"id":"control-api","subject":"*","app":"*","space":"*","policy":"control-api"}]}"""

    /** Revision 1 opens the control API; revision 2, written as a change would be, does not. */
    private fun lockedOut() {
        val file = temp.resolve("platform.json").also { Files.writeString(it, opened) }
        run(
            "publish --to-redis $REDIS --key-prefix $keyPrefix --file $file --document platform"
        ).first.assert().isEqualTo(0)
        controlStore { store ->
            val revisions = Revisions(store)
            val closed = PolicyDocument(
                DocumentKind.PLATFORM,
                "",
                1,
                Instant.parse("2026-11-01T00:00:00Z"),
                emptyList(),
                emptyList()
            )
            revisions.publish(
                SnapshotKey.Platform,
                revisions.head(SnapshotKey.Platform),
                closed,
                Publication("c2", ProvenanceKind.PUBLISH, DocumentPublisher.MIGRATE_ACTOR)
            )
        }
    }

    private fun restore(extra: String) = run(
        "restore --to-redis $REDIS --key-prefix $keyPrefix --document platform $extra"
    )

    @Test
    fun `it prints what it would replace, and writes only with the head confirmed`() {
        lockedOut()
        val (planned, plan) = restore("--revision 1")
        planned.assert().isEqualTo(MigrateMain.USAGE)
        plan.assert().contains("its head, revision 2, is replaced by the content of revision 1, written as revision 3")
            .contains("--confirm 2")
        controlStore { Revisions(it).head(SnapshotKey.Platform)!!.revision }.assert().isEqualTo(2)
        val (refused, wrong) = restore("--revision 1 --confirm 1")
        refused.assert().isEqualTo(MigrateMain.FAILED)
        wrong.assert().contains("Refused").contains("read the plan again")
        val (code, output) = restore("--revision 1 --confirm 2")
        code.assert().describedAs(output).isEqualTo(MigrateMain.OK)
        output.assert().contains("Restored [platform] as revision 3")
        controlStore { Revisions(it).head(SnapshotKey.Platform)!!.document }.policies.map { it.id.value }
            .assert().containsExactly("control-api")
    }

    @Test
    fun `a file restores, and what it needs is asked before anything is read`() {
        lockedOut()
        val file = temp.resolve("restore.json").also { Files.writeString(it, opened) }
        restore("--file $file --confirm 2").first.assert().isEqualTo(MigrateMain.OK)
        restore("").first.assert().isEqualTo(MigrateMain.USAGE)
        restore("--revision 1 --file $file").first.assert().isEqualTo(MigrateMain.USAGE)
        restore("--revision one").second.assert().contains("--revision is a number")
        restore("--revision 1 --confirm two").second.assert().contains("--confirm is the head revision")
        run("restore --to-redis $REDIS --key-prefix $keyPrefix --document nowhere --revision 1").second.assert()
            .contains("--document is platform")
        restore("--file ${temp.resolve("missing.json")}").second.assert().contains("is not a file")
    }

    private companion object {
        const val REDIS = "redis://localhost:6379"
    }
}
