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

package me.ahoo.cosec.spring.boot.starter.pipeline

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.shadow.CachingCandidateSource
import me.ahoo.cosec.runtime.shadow.Candidate
import me.ahoo.cosec.runtime.shadow.CandidateSource
import me.ahoo.cosec.runtime.shadow.CandidateStore
import me.ahoo.cosec.runtime.shadow.StoredCandidate
import me.ahoo.cosec.runtime.snapshot.PlatformCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.cosec.spring.boot.starter.lazily
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import java.time.Instant
import java.util.UUID

/**
 * #1210: a window is forgotten only by a reporter that can read whether its candidate is still staged, so what the
 * starter hands [me.ahoo.cosec.runtime.shadow.ShadowReporter] decides whether ended windows are ever reclaimed.
 */
class ShadowReportingWiringTest {
    private val runner = ReactiveWebApplicationContextRunner()
        // A Spring Cloud Gateway host gets the gateway filter instead; this one is plain WebFlux.
        .withClassLoader(FilteredClassLoader("org.springframework.cloud.gateway.filter.GlobalFilter"))
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
            ),
        )
        .withPropertyValues(
            "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            "cosec.key-prefix=shadow-wiring-${UUID.randomUUID()}",
            "cosec.authorization.routes.location=",
        )

    private val customSource = CandidateSource { null }
    private val customStore = CandidateStore { null }

    @Test
    fun `the reporter reads the store the candidate source reads`() {
        runner.run { context ->
            val source = context.getBean(CandidateSource::class.java) as CachingCandidateSource
            context.getBean(ShadowReporting::class.java).reporter.candidates.assert().isSameAs(source.store)
        }
    }

    @Test
    fun `with an application's own candidate source the reporter reads the only candidate store`() {
        runner
            .withBean("customSource", CandidateSource::class.java, { customSource })
            .withBean("customStore", CandidateStore::class.java, { customStore })
            .run { context ->
                context.getBean(ShadowReporting::class.java).reporter.candidates.assert().isSameAs(customStore)
            }
    }

    @Test
    fun `an application's own candidate source without a candidate store keeps every window`() {
        runner
            .withBean("customSource", CandidateSource::class.java, { customSource })
            .run { context ->
                context.getBean(ShadowReporting::class.java).reporter.candidates.assert().isNull()
            }
    }

    /**
     * SNP-10: the comparison compiles a staged platform candidate; the instance goes on compiling what it enforces with
     * the platform that is published. The candidate here changes the limits, which [PlatformCompiler.compile] would
     * make the live ones, and recompile every installed document with.
     */
    @Test
    fun `a staged platform candidate leaves what the instance compiles with as it was`() {
        val staged = StoredCandidate(
            "w",
            1,
            SnapshotDocument(
                2,
                2,
                Instant.parse("2026-11-01T00:00:00Z"),
                """{"schema":2,"revision":2,"publishedAt":"2026-11-01T00:00:00Z","limits":{"maxNodes":7}}""".encodeToByteArray(),
            ),
        )
        runner
            .withBean(
                "stagedPlatform",
                CandidateStore::class.java,
                { CandidateStore { key -> staged.takeIf { key == SnapshotKey.Platform } } },
            )
            .run { context ->
                val compiler = context.getBean(PlatformCompiler::class.java)
                val live = compiler.current
                val source = context.getBean(CandidateSource::class.java)
                val compiled = runBlocking { source.candidate(SnapshotKey.Platform) }
                compiled.assert().isInstanceOf(Candidate.Ready::class.java)
                compiler.current.assert().isSameAs(live)
            }
    }

    /** `STA-3`: nothing depends on the reporting, so lazily created it would never report; it is created at startup. */
    @Test
    fun `the reporting runs from startup under lazy initialization`() {
        runner.lazily().run { context ->
            context.startupFailure.assert().isNull()
            context.beanFactory.containsSingleton("cosecShadowReporting").assert().isTrue()
        }
    }
}
