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
package me.ahoo.cosec.spring.boot.starter.apikey

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.cache.RedisApiKeyReader
import me.ahoo.cosec.cache.RedisApiKeyStore
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.apikey.ApiKeyReader
import me.ahoo.cosec.spi.apikey.ApiKeyStore
import me.ahoo.cosec.spi.apikey.ApiKeys
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.membership.InMemoryMembershipProvider
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.pipeline.CoSecSnapshotAutoConfiguration
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class CoSecApiKeyAutoConfigurationTest {
    private val key = ApiKeys.generate()

    private val provider = InMemoryMembershipProvider()

    private val contextRunner = ApplicationContextRunner()
        .withPropertyValues("cosec.authorization.routes.location=")
        .withBean(MembershipProvider::class.java, { provider })
        .withConfiguration(
            AutoConfigurations.of(
                CoSecApiKeyAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
            )
        )

    @Test
    fun disabledByDefault() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(ApiKeyStore::class.java)
            val result = runBlocking {
                context.getBean(Authenticator::class.java).authenticate(Credentials(null, key))
            }
            result.assert().isEqualTo(AuthnResult.Anonymous)
        }
    }

    @Test
    fun authenticatesConfiguredKeys() {
        contextRunner
            .withPropertyValues(
                "cosec.api-key.enabled=true",
                "cosec.api-key.keys[0].id=billing",
                "cosec.api-key.keys[0].hash=${ApiKeys.hash(key)}",
                "cosec.api-key.keys[0].principal-id=svc-billing",
                "cosec.api-key.keys[0].tenant-id=acme",
                "cosec.api-key.keys[0].roles=billing,reader",
                "cosec.api-key.keys[0].expires-at=2999-01-01T00:00:00Z",
            )
            .run { context ->
                assertThat(context).hasNotFailed().hasSingleBean(ApiKeyStore::class.java)
                val authenticator = context.getBean(Authenticator::class.java)
                val result = runBlocking { authenticator.authenticate(Credentials(null, key)) }
                result.assert().isInstanceOf(AuthnResult.Authenticated::class.java)
                val principal = (result as AuthnResult.Authenticated).principal
                principal.id.assert().isEqualTo(PrincipalId("svc-billing"))
                principal.tenant.assert().isEqualTo(TenantId("acme"))
                (principal.membershipSource as MembershipSource.Provider).cap!!.keys.assert().containsExactly(
                    SpaceId.DEFAULT
                )
                val unknown = runBlocking { authenticator.authenticate(Credentials(null, ApiKeys.generate())) }
                unknown.assert().isInstanceOf(AuthnResult.Rejected::class.java)
            }
    }

    /** `IDN-6`: a key's memberships are its principal's, so API keys without a MembershipProvider do not start. */
    @Test
    fun apiKeysNeedAMembershipProvider() {
        ApplicationContextRunner()
            .withPropertyValues("cosec.authorization.routes.location=", "cosec.api-key.enabled=true")
            .withConfiguration(
                AutoConfigurations.of(
                    CoSecApiKeyAutoConfiguration::class.java,
                    CoSecSnapshotAutoConfiguration::class.java,
                    CoSecPipelineAutoConfiguration::class.java,
                )
            )
            .run { context ->
                assertThat(context).hasFailed()
                context.startupFailure!!.stackTraceToString().assert()
                    .contains("needs a MembershipProvider bean").contains("CoCacheMembershipProvider").contains("IDN-6")
            }
    }

    @Test
    fun aKeyNeedsATenant() {
        contextRunner
            .withPropertyValues(
                "cosec.api-key.enabled=true",
                "cosec.api-key.keys[0].id=billing",
                "cosec.api-key.keys[0].hash=${ApiKeys.hash(key)}",
                "cosec.api-key.keys[0].principal-id=svc-billing",
            )
            .run { context -> context.startupFailure!!.stackTraceToString().assert().contains("needs a tenant-id") }
    }

    /** A key configured with what keys no longer carry stops startup, rather than losing it silently. */
    @Test
    fun aKeyWithPoliciesOrAttributesDoesNotStart() {
        listOf("policies=p1", "attributes.team=finance").forEach { legacy ->
            contextRunner
                .withPropertyValues(
                    "cosec.api-key.enabled=true",
                    "cosec.api-key.keys[0].id=billing",
                    "cosec.api-key.keys[0].hash=${ApiKeys.hash(key)}",
                    "cosec.api-key.keys[0].principal-id=svc-billing",
                    "cosec.api-key.keys[0].tenant-id=acme",
                    "cosec.api-key.keys[0].$legacy",
                )
                .run { context -> assertThat(context).hasFailed() }
        }
    }

    @Test
    fun rejectsPlainKeysInConfiguration() {
        contextRunner
            .withPropertyValues(
                "cosec.api-key.enabled=true",
                "cosec.api-key.keys[0].id=bad",
                "cosec.api-key.keys[0].hash=$key",
                "cosec.api-key.keys[0].principal-id=svc",
            )
            .run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun redisStoreIsReadWithoutBlocking() {
        contextRunner
            .withConfiguration(
                AutoConfigurations.of(
                    DataRedisAutoConfiguration::class.java,
                    DataRedisReactiveAutoConfiguration::class.java,
                )
            )
            .withPropertyValues("cosec.api-key.enabled=true", "cosec.api-key.store=redis")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(ApiKeyStore::class.java)).isInstanceOf(RedisApiKeyStore::class.java)
                assertThat(context.getBean(ApiKeyReader::class.java)).isInstanceOf(RedisApiKeyReader::class.java)
            }
    }
}
