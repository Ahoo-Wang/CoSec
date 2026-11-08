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

package me.ahoo.cosec.spring.boot.starter.pipeline

import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.runtime.shadow.CachingCandidateSource
import me.ahoo.cosec.runtime.shadow.CandidateSource
import me.ahoo.cosec.runtime.shadow.CandidateStore
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
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
}
