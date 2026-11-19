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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.NoTokenStore
import me.ahoo.cosec.spring.boot.jwt.JwtProperties
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** UB-3: an instance that waits for its platform document says why, once and then at a bounded rate. */
class PlatformDocumentWatchTest {
    private val documents = InMemorySnapshotDocumentStore()

    private val runner = ApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues("${JwtProperties.PREFIX}.enabled=false", "cosec.authorization.routes.location=")
        .withBean(TokenStore::class.java, { NoTokenStore })
        .withBean(TokenFamilyStore::class.java, { InMemoryTokenFamilyStore() })
        .withBean(RevocationReader::class.java, { RevocationReader.NONE })
        .withBean(SnapshotDocumentStore::class.java, { documents })

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
    }

    @Test
    fun `an absent platform document is named at once and again at the interval, then reported installed`() {
        runner.run { context ->
            @Suppress("UNCHECKED_CAST")
            val source = context.getBean(SnapshotSource::class.java) as SnapshotSource<CompiledDocument, CompiledDocument>
            val warnings = CopyOnWriteArrayList<String>()
            val infos = CopyOnWriteArrayList<String>()
            PlatformDocumentWatch(
                source,
                "[acme:snapshot:platform] in Redis redis.internal:6379/2",
                "acme",
                Duration.ofMillis(60),
                warn = { warnings += it },
                info = { infos += it },
                poll = Duration.ofMillis(10),
            ).use {
                waitFor { warnings.size >= 3 }
                warnings.size.assert().isGreaterThanOrEqualTo(3)
                val line = warnings.first()
                line.assert().contains("[acme:snapshot:platform] in Redis redis.internal:6379/2")
                line.assert().contains("cosec-migrate publish --key-prefix acme")
                line.assert().contains("wrong cosec.key-prefix")
                line.assert().contains("wrong Redis database")
                infos.assert().isEmpty()

                documents.put(
                    SnapshotKey.Platform,
                    SnapshotDocuments.read(
                        """{"schema":2,"revision":1,"publishedAt":"1970-01-01T00:00:00Z","policies":[],"bindings":[]}"""
                            .encodeToByteArray()
                    ),
                )
                // The source finds the new document at its next revalidation, which this test does not wait for.
                source.load().get()
                waitFor { infos.isNotEmpty() }
                infos.single().assert().contains("platform document is installed")
            }
        }
    }

    @Test
    fun `a Redis location names the host, the port and the database and never a credential`() {
        val factory = org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
            org.springframework.data.redis.connection.RedisStandaloneConfiguration("redis.internal", 6380).apply {
                database = 3
                setPassword("s3cret")
            }
        )
        val described = RedisLocation.describe(factory)
        described.assert().isEqualTo("redis.internal:6380/3")
        RedisLocation.describe(null).assert().contains("unknown Redis")
    }
}
