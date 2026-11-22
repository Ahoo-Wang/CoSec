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

package me.ahoo.cosec.spring.boot.pipeline

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisURI
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.output.StatusOutput
import io.lettuce.core.protocol.CommandArgs
import io.lettuce.core.protocol.CommandType
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.shadow.CachingCandidateSource
import me.ahoo.cosec.runtime.shadow.CandidateSource
import me.ahoo.cosec.runtime.shadow.ShadowRecorder
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.shadow.ShadowReportSink
import me.ahoo.cosec.spi.shadow.StagedCandidates
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.NoTokenStore
import me.ahoo.cosec.spring.boot.control.CoSecControlAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.JwtProperties
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.io.File
import java.time.Duration
import java.util.UUID

/**
 * `SNP-18` on real Redis: the data-plane user of `wiki/operations/storage.md`, exactly the two lines the page shows
 * (its user, key prefix and instance renamed), is enough for an instance that does not run the control plane. It
 * starts, reads the documents read-only, hears a publish from a control-plane user, writes its own report, and leaves
 * nothing in `ACL LOG`; Redis itself refuses its writes of a document and of a candidate. The lines 8.0.0-beta.1's page
 * gave fail startup, and so does a process that runs the control plane with the read-only user.
 *
 * It changes the ACL of the Redis it runs on: on CI, the job's own Redis, always (a Redis it cannot reach, or ACL
 * commands it is refused, fail it); elsewhere only a Redis that `ACL_TEST_REDIS` names (`redis://host:port`), and
 * it is skipped otherwise. Not `COSEC_…`: Spring would bind that as a `cosec.*` property, which the starter's
 * unknown-property check refuses in every other test of the module. Its users have unique names and are deleted afterwards, and `ACL LOG` is read by user.
 */
class DataPlaneAclTest {
    private val id = UUID.randomUUID().toString().substring(0, 8)
    private val prefix = "acl-$id"
    private val instance = "pod-$id"
    private val user = "cosec-acl-$id"
    private lateinit var target: RedisURI
    private lateinit var client: RedisClient
    private lateinit var admin: RedisCommands<String, String>

    @BeforeEach
    fun connect() {
        val configured = System.getenv(TARGET) ?: if (System.getenv("CI") != null) CI_REDIS else null
        Assumptions.assumeTrue(configured != null, "$TARGET names no Redis of your own: this test changes its ACL")
        target = RedisURI.create(configured)
        client = RedisClient.create(target)
        admin = client.connect().sync()
    }

    @AfterEach
    fun disconnect() {
        if (::client.isInitialized) {
            admin.aclDeluser(user)
            client.shutdown()
        }
    }

    private fun setUser(vararg lines: String) {
        for (line in lines) {
            val rules = line.replace("cosec-pod-a", user)
                .replace("cosec:", "$prefix:")
                .replace(":pod-a", ":$instance")
                .split(" ")
                .drop(3)
            val args = CommandArgs(StringCodec.UTF8).add("SETUSER").add(user)
            rules.forEach { args.add(it) }
            admin.dispatch(CommandType.ACL, StatusOutput(StringCodec.UTF8), args)
        }
    }

    private fun aclLog(): List<Map<String, Any>> = admin.aclLog().filter { it["username"] == user }

    private fun url(name: String?): String {
        // The test's own connection is the default user's, as on CI; a named user signs in with the page's password.
        val credentials = name?.let { "$it:change-me@" }.orEmpty()
        return "redis://$credentials${target.host}:${target.port}"
    }

    private val common = arrayOf(
        "cosec.key-prefix=$prefix",
        "cosec.ip2region.enabled=false",
        "cosec.authorization.routes.location=",
        // Longer than the test waits: the instance learns of a publish only by its invalidation.
        "cosec.authorization.snapshot.revalidate-interval=50s",
        "cosec.authorization.shadow.instance-id=$instance",
    )

    /**
     * An instance without the control plane, connected as [name]: `cosec-control` is not on its classpath, or, for
     * [oneImage], it is and `cosec.control.enabled=false` switches it off.
     */
    private fun dataPlane(name: String?, oneImage: Boolean = false) = ApplicationContextRunner()
        .withClassLoader(
            if (oneImage) {
                FilteredClassLoader(GATEWAY_GLOBAL_FILTER)
            } else {
                FilteredClassLoader(GATEWAY_GLOBAL_FILTER, "me.ahoo.cosec.control.api.ControlHttp")
            }
        )
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                DataRedisAutoConfiguration::class.java,
                DataRedisReactiveAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
                CoSecControlAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(*if (oneImage) arrayOf("cosec.control.enabled=false") else emptyArray())
        .withPropertyValues("spring.data.redis.url=${url(name)}", "${JwtProperties.PREFIX}.enabled=false", *common)
        .withBean(TokenStore::class.java, { NoTokenStore })
        .withBean(TokenFamilyStore::class.java, { InMemoryTokenFamilyStore() })
        .withBean(RevocationReader::class.java, { RevocationReader.NONE })

    /** A process that runs the control plane, connected as [name] (the default user when null). */
    private fun controlPlane(name: String?) = ApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                DataRedisAutoConfiguration::class.java,
                DataRedisReactiveAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecTokenRevocationCacheAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
                CoSecControlAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "spring.data.redis.url=${url(name)}",
            "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            *common,
        )

    private fun publish(context: AssertableApplicationContext, revision: Long) {
        val revisions = context.getBean(Revisions::class.java)
        runBlocking {
            val head = revisions.head(SnapshotKey.Platform)
            val text = """{"schema":2,"revision":$revision,"publishedAt":"2026-11-18T00:00:00Z","policies":[],"bindings":[]}"""
            revisions.publish(
                SnapshotKey.Platform,
                head,
                PolicyDocumentCodec.decode(DocumentKind.PLATFORM, "", text.encodeToByteArray()),
                Publication("acl", ProvenanceKind.BOOTSTRAP, Actor(TenantId.PLATFORM, PrincipalId("plato"))),
            )
        }
    }

    private fun installed(context: AssertableApplicationContext): Long? {
        @Suppress("UNCHECKED_CAST")
        val source = context.getBean(SnapshotSource::class.java) as SnapshotSource<CompiledDocument, CompiledDocument>
        return source.peek()?.policiesEntry?.revision
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(50)
        }
    }

    private fun failures(context: AssertableApplicationContext): String =
        generateSequence(context.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun `the page's data-plane user reads, hears a publish, writes its report, and may write no document`() {
        setUser(KEYS_LINE, COMMANDS_LINE)
        controlPlane(null).run { control ->
            control.startupFailure.assert().isNull()
            publish(control, 1)
            dataPlane(user).run { node ->
                node.startupFailure.assert().describedAs(failures(node)).isNull()
                eventually { installed(node) == 1L }
                installed(node).assert().isEqualTo(1L)
                publish(control, 2)
                eventually { installed(node) == 2L }
                installed(node).assert().isEqualTo(2L)
                // The configured id is the prefix of this start's id: the ACL's `pod-a.*` is what lets it write.
                val started = node.getBean(ShadowRecorder::class.java).instance
                started.assert().startsWith("$instance.")
                runBlocking {
                    node.getBean(ShadowReportSink::class.java)
                        .write(ShadowReport("w-$id", started, emptyMap(), emptyMap(), emptySet(), null, null))
                }
                admin.exists("$prefix:shadow:report:w-$id:$started").assert().isEqualTo(1L)
                // It reads the staged list the control plane keeps (CTL-47): a read the page's lines allow.
                val candidates = node.getBean(CandidateSource::class.java) as CachingCandidateSource
                // Kept (empty) once the control plane's first shadow pass ran, absent before it: either is a read.
                val list = runBlocking { candidates.store.staged() }
                (list == null || list == StagedCandidates.NONE).assert().describedAs("staged list $list").isTrue()
                aclLog().assert().describedAs("ACL LOG of $user").isEmpty()
            }
        }
        // Redis itself refuses the data-plane user a write of a document or a candidate.
        val own = RedisClient.create(url(user))
        try {
            val commands = own.connect().sync()
            for (key in listOf("$prefix:snapshot:platform", "$prefix:candidate:platform")) {
                assertThrows<RedisCommandExecutionException> { commands.set(key, "{}") }
                    .message.orEmpty().assert().startsWith("NOPERM")
            }
        } finally {
            own.shutdown()
        }
        aclLog().map { it["object"] }.toSet()
            .assert().isEqualTo(setOf("$prefix:snapshot:platform", "$prefix:candidate:platform"))
    }

    /** One image for both roles: the control plane on the classpath, switched off, and the page's read-only user. */
    @Test
    fun `an image with the control plane, switched off, starts with the page's user and may write no document`() {
        setUser(KEYS_LINE, COMMANDS_LINE)
        controlPlane(null).run { control ->
            control.startupFailure.assert().isNull()
            publish(control, 1)
            dataPlane(user, oneImage = true).run { node ->
                node.startupFailure.assert().describedAs(failures(node)).isNull()
                node.getBeansOfType(ControlHttp::class.java).assert().isEmpty()
                eventually { installed(node) == 1L }
                installed(node).assert().isEqualTo(1L)
                aclLog().assert().describedAs("ACL LOG of $user").isEmpty()
            }
        }
        val own = RedisClient.create(url(user))
        try {
            assertThrows<RedisCommandExecutionException> { own.connect().sync().set("$prefix:snapshot:platform", "{}") }
                .message.orEmpty().assert().startsWith("NOPERM")
        } finally {
            own.shutdown()
        }
    }

    @Test
    fun `the lines of 8_0_0-beta_1's page fail startup, naming what is missing`() {
        setUser(*BETA_1_LINES)
        dataPlane(user).run { instance ->
            instance.startupFailure.assert().isNotNull()
            failures(instance).assert().contains("Redis denied the command [evalsha_ro]: allow [+evalsha_ro]")
        }
    }

    @Test
    fun `a process that runs the control plane with the read-only user fails at startup`() {
        setUser(KEYS_LINE, COMMANDS_LINE)
        controlPlane(user).run { process ->
            // The control plane writes the documents: its snapshot store is read-write, and CoCache's publish probe
            // finds the user may not publish its invalidations (the misdeclared deployment is loud).
            process.startupFailure.assert().isNotNull()
            failures(
                process
            ).assert().contains("Redis denied publishing to the invalidation channel[$prefix:inv:snapshot]")
        }
    }

    @Test
    fun `the operations page shows the lines this test runs, and upgrading says what to add`() {
        for (page in listOf("../../wiki/operations/storage.md", "../../wiki/zh/operations/storage.md")) {
            File(page).readText().assert().contains(KEYS_LINE).contains(COMMANDS_LINE)
        }
        for (
        page in listOf(
            "../../wiki/operations/upgrading-previews.md",
            "../../wiki/zh/operations/upgrading-previews.md",
        )
        ) {
            File(page).readText().assert().contains("+evalsha_ro +eval_ro")
        }
    }

    private companion object {
        const val TARGET = "ACL_TEST_REDIS"
        const val CI_REDIS = "redis://localhost:6379"

        /** The data-plane user's keys, as `storage.md` shows them (default prefix, instance `pod-a`). */
        const val KEYS_LINE = "ACL SETUSER cosec-pod-a on >change-me resetkeys resetchannels -@all " +
            "%R~cosec:snapshot:* %R~cosec:candidate:* %R~cosec:candidate-staged:* ~cosec:shadow:report: " +
            "~cosec:shadow:report:*:pod-a.* " +
            "~cosec:shadow:reporters:* ~cosec:shadow:status: ~cosec:shadow:status:pod-a.* " +
            "~cosec:shadow:resident: ~cosec:shadow:resident:pod-a.* ~cosec:shadow:instances:* ~cocache:!probe:*"

        /** Its commands and channels, as `storage.md` shows them (`channel-mode: pubsub`). */
        const val COMMANDS_LINE = "ACL SETUSER cosec-pod-a +@connection +evalsha +eval +evalsha_ro +eval_ro " +
            "+publish +subscribe +unsubscribe +info +get +getrange +strlen +set +del +pttl +type +hgetall +hkeys " +
            "+hmget +hset +hdel +hlen +pexpire +persist +setrange +time &cosec:* &cocache:!probe:*"

        /** The two lines of 8.0.0-beta.1's page, which gave read-write keys and no read-only scripts. */
        val BETA_1_LINES = arrayOf(
            "ACL SETUSER cosec-pod-a on >change-me resetkeys resetchannels -@all ~cosec:snapshot:* " +
                "~cosec:candidate:* ~cosec:shadow:report: ~cosec:shadow:report:*:pod-a ~cosec:shadow:reporters:* " +
                "~cosec:shadow:status: ~cosec:shadow:status:pod-a ~cosec:shadow:instances:* ~cocache:!probe:*",
            "ACL SETUSER cosec-pod-a +@connection +evalsha +eval +publish +subscribe +unsubscribe +info +get " +
                "+getrange +strlen +set +del +pttl +type +hgetall +hkeys +hmget +hset +hdel +hlen +pexpire +persist " +
                "+setrange +time &cosec:* &cocache:!probe:*",
        )
    }
}
