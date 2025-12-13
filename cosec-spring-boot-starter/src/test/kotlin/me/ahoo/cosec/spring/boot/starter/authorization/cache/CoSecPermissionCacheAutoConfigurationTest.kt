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

package me.ahoo.cosec.spring.boot.starter.authorization.cache

import me.ahoo.cache.spring.boot.starter.CoCacheAutoConfiguration
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.cache.AppPermissionCache
import me.ahoo.cosec.cache.RolePermissionCache
import me.ahoo.cosid.IdGenerator
import me.ahoo.cosid.test.MockIdGenerator
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class CoSecPermissionCacheAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .withBean(IdGenerator::class.java, { MockIdGenerator.INSTANCE })
            .withUserConfiguration(
                DataRedisAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                CoSecPermissionCacheAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                assertThat(context)
                    .hasSingleBean(CacheProperties::class.java)
                    .hasSingleBean(CoSecPermissionCacheAutoConfiguration::class.java)
                    .hasBean(CoSecPermissionCacheAutoConfiguration.APP_PERMISSION_CACHE_BEAN_NAME)
                    .hasSingleBean(AppPermissionCache::class.java)
                    .hasBean(CoSecPermissionCacheAutoConfiguration.ROLE_PERMISSION_CACHE_BEAN_NAME)
                    .hasSingleBean(RolePermissionCache::class.java)
                    .hasSingleBean(AppRolePermissionRepository::class.java)
            }
    }
}
