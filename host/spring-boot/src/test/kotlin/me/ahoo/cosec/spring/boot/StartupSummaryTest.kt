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

package me.ahoo.cosec.spring.boot

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.jwt.JwtProperties
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineIntegrationAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecSnapshotAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** UB-5: startup says what CoSec is configured as, and that it is off when it is. */
class StartupSummaryTest {
    private val runner = ApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                CoSecPropertyCheckAutoConfiguration::class.java,
                CoSecAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues("${JwtProperties.PREFIX}.enabled=false", "cosec.authorization.routes.location=")
        .withBean(TokenStore::class.java, { NoTokenStore })
        .withBean(TokenFamilyStore::class.java, { InMemoryTokenFamilyStore() })
        .withBean(RevocationReader::class.java, { RevocationReader.NONE })
        .withBean(SnapshotDocumentStore::class.java, { InMemorySnapshotDocumentStore() })

    @Test
    fun `the summary names the mode, the rate limiter, the listeners and the route map`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            val report = StartupSummary.report(context, context.environment)
            report.assert().isInstanceOf(StartupSummary.Report.On::class.java)
            val line = report.line
            line.assert().startsWith("CoSec")
            line.assert().contains("mode=enforce")
            line.assert().contains("rate-limiter=LocalRateLimiter")
            line.assert().contains("revocation-reader=")
            line.assert().contains("listeners=[")
            line.assert().contains("route-map=none")
            line.assert().contains("api-keys=off")
        }
    }

    @Test
    fun `cosec enabled false is one warning that nothing is protected`() {
        runner.withPropertyValues("cosec.enabled=false").run { context ->
            context.startupFailure.assert().isNull()
            val report = StartupSummary.report(context, context.environment)
            report.assert().isInstanceOf(StartupSummary.Report.Off::class.java)
            report.line.assert().contains("CoSec is off").contains("cosec.enabled=false").contains("nothing is protected")
        }
    }
}
