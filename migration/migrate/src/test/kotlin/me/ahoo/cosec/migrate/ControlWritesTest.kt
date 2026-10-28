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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.migrate.Fixtures7x.policy
import me.ahoo.cosec.migrate.Fixtures7x.source
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import me.ahoo.cosec.snapshot.document.ProvenanceKind
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * RFC 0007 §5.9, §8, §9.4: `cosec-migrate` writes through the control plane's store, so its writes leave revision
 * records; `bootstrap` binds the first platform administrator once; `publish` refuses a long-lived root; and
 * `partition plan` copies each tenant's attachable policies exactly.
 */
class ControlWritesTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)

    @TempDir
    lateinit var temp: Path

    @Test
    fun `bootstrap binds the first platform administrator once, and recovers only when forced`() = runBlocking<Unit> {
        val bootstrap = Bootstrap(store, clock)
        bootstrap.run(PrincipalId("alice")).assert().isEqualTo(1)
        val head = revisions.head(Bootstrap.KEY)!!
        head.document.provenance!!.kind.assert().isEqualTo(ProvenanceKind.BOOTSTRAP)
        val binding = head.document.bindings.single()
        binding.subject.assert().isEqualTo(SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("alice")))
        binding.policy.assert().isEqualTo(BuiltinPolicies.PLATFORM_ADMIN)
        revisions.history(Bootstrap.KEY).size.assert().isEqualTo(1)
        assertThrownBy<BootstrapRefusedException> { bootstrap.run(PrincipalId("mallory")) }
        bootstrap.run(PrincipalId("bob"), force = true).assert().isEqualTo(2)
        revisions.head(
            Bootstrap.KEY
        )!!.document.bindings.map { it.id.value }.assert().containsExactly("platform-admin-alice", "platform-admin-bob")
        // Bootstrapping a principal already bound writes nothing new.
        bootstrap.run(PrincipalId("bob"), force = true).assert().isEqualTo(2)
    }

    @Test
    fun `publish starts the revision chain and refuses a root that outlives break-glass`() {
        val converted = Converter().convert(source(listOf("p" to policy("p"))), ConvertOptions(publishedAt = now))
        DocumentPublisher(
            store,
            clock = clock
        ).publish(converted.documents).written.assert().containsExactly(SnapshotKey.Platform)
        runBlocking {
            revisions.head(SnapshotKey.Platform)!!.document.provenance!!.kind.assert().isEqualTo(ProvenanceKind.MIGRATE)
            revisions.history(SnapshotKey.Platform).size.assert().isEqualTo(1)
        }
        // A converted root valid for 30 days is refused; within the cap it publishes.
        val root = { expiresAt: Instant ->
            Converter().convert(
                source(listOf("p" to policy("p"))),
                ConvertOptions(root = RootOptions(expiresAt = expiresAt), publishedAt = now)
            ).documents
        }
        val otherStore = InMemoryControlStore(clock)
        assertThrownBy<PublishException> {
            DocumentPublisher(
                otherStore,
                clock = clock
            ).publish(root(now.plus(Duration.ofDays(30))))
        }
            .hasMessageContaining("break-glass through the control plane").hasMessageContaining("Nothing was written")
        DocumentPublisher(
            otherStore,
            clock = clock
        ).publish(root(now.plus(Duration.ofHours(3)))).written.assert().containsExactly(SnapshotKey.Platform)
        assertThrownBy<PublishException> {
            DocumentPublisher(
                InMemoryControlStore(clock),
                clock = clock,
                breakGlassMaxDuration = Duration.ofHours(1)
            ).publish(root(now.plus(Duration.ofHours(3))))
        }
    }

    @Test
    fun `partition plan copies each tenant's attachable policies exactly`() {
        val source = source(
            listOf(
                "g" to policy("g", "global"),
                "c" to policy("c", tenantId = "acme"),
                "d" to policy("d", tenantId = "globex"),
                "p" to policy("p", tenantId = "(platform)"),
            ),
            setOf("g"),
        )
        val plan = PartitionPlan.of(
            Converter().convert(source, ConvertOptions(Partition.PLATFORM, publishedAt = now)),
            Converter().convert(source, ConvertOptions(Partition.BY_OWNER, publishedAt = now)),
        )
        plan.keys.assert().containsExactlyInAnyOrder(SnapshotKey.Tenant("acme"), SnapshotKey.Tenant("globex"))
        plan.getValue(SnapshotKey.Tenant("acme")).policies.single().id.value.assert().isEqualTo("c")
        val files = PartitionPlan.write(plan, temp.resolve("plan"))
        files.map { it.fileName.toString() }.assert().containsExactlyInAnyOrder(
            "tenant-acme.json",
            "tenant-globex.json"
        )
        val written = PolicyDocumentCodec.decode(
            me.ahoo.cosec.model.decision.DocumentKind.TENANT,
            "acme",
            Files.readAllBytes(temp.resolve("plan/tenant-acme.json"))
        )
        written.policies.single().attachable.assert().isTrue()
    }

    @Test
    fun `the commands check their options`() {
        val out = java.io.ByteArrayOutputStream()
        val print = java.io.PrintStream(out, true, Charsets.UTF_8)
        MigrateMain.run(
            listOf("bootstrap", "--to-redis", "redis://localhost:6379"),
            print
        ).assert().isEqualTo(MigrateMain.USAGE)
        MigrateMain.run(
            listOf("partition", "plan", "--out", temp.toString()),
            print
        ).assert().isEqualTo(MigrateMain.USAGE)
        MigrateMain.run(
            listOf(
                "publish",
                "--to-redis",
                "redis://localhost:6379",
                "--in",
                temp.toString(),
                "--break-glass-max-duration",
                "4h"
            ),
            print
        )
            .assert().isIn(MigrateMain.USAGE, MigrateMain.FAILED)
    }
}
