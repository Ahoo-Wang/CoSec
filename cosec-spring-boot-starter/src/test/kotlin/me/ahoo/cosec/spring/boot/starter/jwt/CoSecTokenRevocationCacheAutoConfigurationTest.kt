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
package me.ahoo.cosec.spring.boot.starter.jwt

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cache.proxy.CacheProxyFactory
import me.ahoo.cosec.cache.CoCacheTokenStore
import me.ahoo.cosec.cache.RevokedTokenCache
import me.ahoo.cosec.token.TokenStore
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.StringRedisTemplate

class CoSecTokenRevocationCacheAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoadsWhenDisabled() {
        contextRunner
            .withPropertyValues(
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            )
            .withBean(StringRedisTemplate::class.java, { mockk(relaxed = true) })
            .withUserConfiguration(
                CoSecTokenRevocationCacheAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .doesNotHaveBean(CoCacheTokenStore::class.java)
                context.getBean(TokenStore::class.java).assert().isSameAs(TokenStore.NoOp)
            }
    }

    @Test
    fun contextLoadsWhenEnabled() {
        contextRunner
            .withPropertyValues(
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
                "${JwtProperties.PREFIX}.token-revocation.enabled=true",
            )
            .withBean(StringRedisTemplate::class.java, { mockk(relaxed = true) })
            .withBean(CacheProxyFactory::class.java, {
                mockk {
                    every { create<RevokedTokenCache>(any()) } returns mockk()
                }
            })
            .withUserConfiguration(
                CoSecTokenRevocationCacheAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(RevokedTokenCache::class.java)
                    .hasSingleBean(TokenStore::class.java)
                context.getBean(TokenStore::class.java)
                    .assert()
                    .isInstanceOf(CoCacheTokenStore::class.java)
            }
    }
}
