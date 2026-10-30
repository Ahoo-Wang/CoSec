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

import io.lettuce.core.RedisURI
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.control.CoCacheControlStore
import me.ahoo.cosec.cache.snapshot.createPolicySnapshotCache
import me.ahoo.cosec.control.store.HeadWrite
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.RevisionIndex
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.migrate.Fixtures7x.policy
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
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
import java.time.Instant
import java.util.UUID

class MigrateMainTest {
    @TempDir
    lateinit var temp: Path

    private fun run(args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = PrintStream(
            out,
            true,
            Charsets.UTF_8
        ).use { MigrateMain.run(args.split(' ').filter { it.isNotEmpty() }, it) }
        return code to out.toString(Charsets.UTF_8)
    }

    private fun export(policies: String = "[]"): Path {
        val directory = Files.createDirectories(temp.resolve("export"))
        Files.writeString(directory.resolve(ExportDirectory.POLICIES_FILE), policies)
        Files.writeString(directory.resolve(ExportDirectory.GLOBAL_POLICIES_FILE), "[]")
        Files.writeString(directory.resolve(ExportDirectory.APP_ROLE_PERMISSIONS_FILE), "[]")
        return directory
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "verify --in x",
            "convert --out x",
            "convert --from-files a --from-redis b --out x",
            "convert --from-files a",
            "convert --from-files a --out x --partition tenant",
            "convert --from-files a --out x --root-expires-at tomorrow",
            "convert --from-files a --out x --unknown y",
            "convert --from-files a --out x --root-enabled --root-enabled",
            "convert --from-files a --out x --out y",
            "convert --from-files",
            "convert from-files",
            "publish --in x",
        ],
    )
    fun `usage errors exit with 2`(args: String) {
        val (code, output) = run(args)
        code.assert().describedAs(output).isEqualTo(MigrateMain.USAGE)
        output.assert().contains("Usage:")
    }

    @Test
    fun `convert from files writes the documents`() {
        val out = temp.resolve("out")
        val (code, output) = run("convert --from-files ${export("[${policy("c")}]")} --out $out")
        code.assert().describedAs(output).isEqualTo(MigrateMain.OK)
        output.assert().contains("attachablePolicies: 1")
        Files.isRegularFile(out.resolve(ConvertedDirectory.PLATFORM_FILE)).assert().isTrue()
    }

    @Test
    fun `a root without an expiry is rejected, and the console says how to fix it`() {
        val out = temp.resolve("out")
        val (code, output) = run("convert --from-files ${export()} --out $out --root-enabled --root-id admin")
        code.assert().isEqualTo(MigrateMain.FAILED)
        output.assert().contains("REJECT ROOT_WITHOUT_EXPIRY").contains("pass --root-expires-at")
    }

    @Test
    fun `a root with an expiry and by-owner partition convert`() {
        val out = temp.resolve("out")
        val (code, output) = run(
            "convert --from-files ${export("[${policy("c", tenantId = "acme")}]")} --out $out " +
                "--root-expires-at 2026-12-31T00:00:00Z --partition by-owner",
        )
        code.assert().describedAs(output).isEqualTo(MigrateMain.OK)
        output.assert().contains("semantic change C1d").contains("semantic change C5")
        Files.isRegularFile(out.resolve("tenants/acme.json")).assert().isTrue()
    }

    @Test
    fun `a failure that is not the data's exits with 1`() {
        val (code, output) = run("convert --from-files ${temp.resolve("absent")} --out ${temp.resolve("out")}")
        code.assert().isEqualTo(MigrateMain.FAILED)
        output.assert().contains("Failed:")
        run(
            "publish --to-redis redis://localhost:6379 --in ${temp.resolve("absent")}"
        ).first.assert().isEqualTo(MigrateMain.FAILED)
    }

    /** The control plane's store on the local Redis under [keyPrefix], as the commands build it. */
    private fun <T> controlStore(keyPrefix: String, block: suspend (CoCacheControlStore) -> T): T =
        RedisBackend.standalone(RedisURI.create(REDIS)).use { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val heads = runtime.createPolicySnapshotCache(CoSecCacheSpecs.policySnapshot(keyPrefix))
                runBlocking { block(CoCacheControlStore.create(runtime, heads, keyPrefix)) }
            }
        }

    private fun document(key: SnapshotKey, policies: List<String>, bindings: List<String> = emptyList()): PolicyDocument {
        val (kind, owner) = Revisions.ownerOf(key)
        return PolicyDocument(
            kind,
            owner,
            1,
            Instant.now(),
            policies.map {
                PolicyDefinition(
                    PolicyId(it),
                    false,
                    null,
                    listOf(StatementSource("", Effect.ALLOW, listOf("*")))
                )
            },
            bindings.map {
                Binding(
                    BindingId(it),
                    SubjectRef.Role(null, RoleId("staff")),
                    BindingScope(Match.Any, Match.Any),
                    PolicyId(it)
                )
            },
        )
    }

    /** RC1-9: after the upgrade, the tenants' own policy ids enter the back-reference index. */
    @Test
    fun `repair-references adds what the heads refer to, adds nothing again, and fails on a head it cannot read`() {
        val keyPrefix = "migrate-test:${UUID.randomUUID()}"
        val acme = SnapshotKey.Tenant("acme")
        val own = PolicyId("own")
        controlStore(keyPrefix) { store ->
            val revisions = Revisions(store)
            val publication = Publication("c", ProvenanceKind.MIGRATE, Actor(TenantId.PLATFORM, PrincipalId("op")))
            revisions.publish(SnapshotKey.Platform, null, document(SnapshotKey.Platform, listOf("shared")), publication)
            revisions.publish(acme, null, document(acme, listOf("own"), listOf("shared", "own")), publication)
            // As published before the upgrade: acme's own id is not in the index.
            val indexed = store.referrers(SnapshotKey.Platform, own)!!
            store.putReferrers(SnapshotKey.Platform, own, indexed.digest, emptySet()).assert().isTrue()
        }
        val args = "repair-references --to-redis $REDIS --key-prefix $keyPrefix"
        val (code, output) = run(args)
        code.assert().describedAs(output).isEqualTo(MigrateMain.OK)
        output.assert().contains("Visited 2 document(s); added 1 reference(s).")
        controlStore(keyPrefix) { store ->
            store.referrers(SnapshotKey.Platform, own)!!.value.assert().containsExactly(acme)
            store.referrers(SnapshotKey.Platform, PolicyId("shared"))!!.value.assert().containsExactly(acme)
        }
        run(args).let { (again, out) ->
            again.assert().describedAs(out).isEqualTo(MigrateMain.OK)
            out.assert().contains("Visited 2 document(s); added 0 reference(s).")
        }
        // A listed document whose head does not decode (the strict codec refuses an unknown member) is named, and the
        // run fails; the others are still visited.
        val broken = SnapshotKey.Tenant("broken")
        controlStore(keyPrefix) { store ->
            store.putIndex(broken, null, RevisionIndex()).assert().isTrue()
            val body = """{"schema":${PolicyDocumentCodec.SCHEMA},"revision":1,"publishedAt":"2026-11-01T00:00:00Z","unknown":1}"""
            val garbage = SnapshotDocument(
                PolicyDocumentCodec.SCHEMA,
                1,
                Instant.parse("2026-11-01T00:00:00Z"),
                body.encodeToByteArray()
            )
            store.putHead(broken, null, garbage).assert().isInstanceOf(HeadWrite.Written::class.java)
        }
        val (failed, out) = run(args)
        failed.assert().describedAs(out).isEqualTo(MigrateMain.FAILED)
        out.assert().contains("FAILED tenant:broken").contains("Visited 3 document(s); added 0 reference(s).")
    }

    private companion object {
        const val REDIS = "redis://localhost:6379"
    }
}
