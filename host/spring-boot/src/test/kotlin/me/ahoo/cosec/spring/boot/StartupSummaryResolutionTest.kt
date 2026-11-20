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

import me.ahoo.cocache.core.CacheLayers
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.cocache.CoCacheTokenStore
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.RevokedTokenCache
import me.ahoo.cosec.jwt.JwtSigning
import me.ahoo.cosec.jwt.VerifiedTokenCache
import me.ahoo.cosec.spi.apikey.ApiKeyReader
import me.ahoo.cosec.spi.membership.MembershipAnswer
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.token.InMemoryTokenFamilyStore
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.spi.token.TokenFamilyStore
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spi.token.VerifiedToken
import me.ahoo.cosec.spring.boot.apikey.CoSecApiKeyAutoConfiguration
import me.ahoo.cosec.spring.boot.authorization.cache.createCoSecCache
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineIntegrationAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecSnapshotAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext

/**
 * UB-5: each entry of the summary is what a request will meet, resolved as the authenticator resolves it, never a list of
 * the beans of a type where the starter derives the thing: with `cosec-cocache` there is no `RevocationReader` bean, and
 * the summary must not say there is no reader.
 */
class StartupSummaryResolutionTest {
    class OwnReader : RevocationReader by RevocationReader.NONE

    class OwnVerifier : TokenVerifier {
        override fun verify(accessToken: String): VerifiedToken = throw UnsupportedOperationException()

        override fun refresh(token: CompositeToken): VerifiedToken = throw UnsupportedOperationException()
    }

    class OwnApiKeys : ApiKeyReader {
        override suspend fun findByHash(hash: String) = null
    }

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
        .withPropertyValues("cosec.jwt.enabled=false", "cosec.authorization.routes.location=")
        .withBean(TokenFamilyStore::class.java, { InMemoryTokenFamilyStore() })
        .withBean(SnapshotDocumentStore::class.java, { InMemorySnapshotDocumentStore() })

    private val withAlgorithm = runner
        .withBean(JwtSigning::class.java, { JwtSigning.hmac256("0123456789abcdef0123456789abcdef") })

    private val withTokens = withAlgorithm.withBean(TokenStore::class.java, { NoTokenStore })

    private fun line(context: ApplicationContext): String = StartupSummary.report(context, context.environment).line

    @Test
    fun `without tokens nothing is said of revocations`() {
        runner.withBean(TokenStore::class.java, {
            NoTokenStore
        }).withBean(RevocationReader::class.java, { RevocationReader.NONE })
            .run { context ->
                context.startupFailure.assert().isNull()
                val line = line(context)
                line.assert().contains("jwt=off").doesNotContain("revocation-reader")
            }
    }

    @Test
    fun `the CoCache token store's reader is named, not none`() {
        CacheRuntime.builder().backend(InMemoryBackend()).build().use { cache ->
            val factory = CoCacheFactory(cache, CacheLayers.NONE)
            val revoked = factory.createCoSecCache(RevokedTokenCache::class.java, CoSecCacheSpecs.revokedToken("acme"))
            withAlgorithm.withBean(TokenStore::class.java, { CoCacheTokenStore(revoked) }).run { context ->
                context.startupFailure.assert().isNull()
                val line = line(context)
                line.assert().contains("jwt=on (JwtTokenVerifier)")
                    .contains("token-cache=on (max-entries=16384)")
                    .contains("revocation-reader=the CoCache token store's (CoCacheRevocationReader)")
                    .doesNotContain("NONE").doesNotContain("revocation-reader=none")
            }
        }
    }

    @Test
    fun `an application's own reader is named as a bean`() {
        withTokens.withBean(RevocationReader::class.java, { OwnReader() }).run { context ->
            context.startupFailure.assert().isNull()
            line(context).assert().contains("revocation-reader=bean OwnReader")
        }
    }

    @Test
    fun `NONE is named as NONE`() {
        withTokens.withBean(RevocationReader::class.java, { RevocationReader.NONE }).run { context ->
            context.startupFailure.assert().isNull()
            line(context).assert().contains("revocation-reader=RevocationReader.NONE (revoked tokens are accepted")
        }
    }

    @Test
    fun `an application's own token verifier is the one named`() {
        withTokens.withBean(RevocationReader::class.java, { RevocationReader.NONE })
            .withBean(TokenVerifier::class.java, { OwnVerifier() }).run { context ->
                context.startupFailure.assert().isNull()
                line(context).assert().contains("jwt=on (bean OwnVerifier)")
                    .contains("token-cache=off (the application's own verifier)")
            }
    }

    @Test
    fun `the token cache is off when configured off`() {
        withTokens.withBean(RevocationReader::class.java, { RevocationReader.NONE })
            .withPropertyValues("cosec.authentication.token-cache.enabled=false").run { context ->
                context.startupFailure.assert().isNull()
                context.getBeanNamesForType(VerifiedTokenCache::class.java).assert().isEmpty()
                line(context).assert().contains("token-cache=off,")
            }
    }

    @Test
    fun `API keys name the reader a request meets, the application's or the in-memory store`() {
        // A pipeline that answers API keys has a membership provider (UA-12).
        val keys = withTokens.withBean(RevocationReader::class.java, { RevocationReader.NONE })
            .withPropertyValues("cosec.api-key.enabled=true")
            .withBean(MembershipProvider::class.java, { MembershipProvider { _, _ -> MembershipAnswer.Unknown } })
        keys.withBean(ApiKeyReader::class.java, { OwnApiKeys() }).run { context ->
            context.startupFailure.assert().isNull()
            line(context).assert().contains("api-keys=on (bean OwnApiKeys)")
        }
        keys.withConfiguration(AutoConfigurations.of(CoSecApiKeyAutoConfiguration::class.java))
            .run { context ->
                context.startupFailure.assert().isNull()
                line(context).assert().contains("api-keys=on (the in-memory store)")
            }
    }
}
