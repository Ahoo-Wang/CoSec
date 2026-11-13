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

package me.ahoo.cosec.spring.boot.starter

import me.ahoo.cocache.spring.CacheNameContributor
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.CoSecCacheSpecs
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** `cosec.enabled`: switches every CoSec auto-configuration off. */
@ConditionalOnCoSecEnabled
@AutoConfiguration
@EnableConfigurationProperties(CoSecProperties::class)
internal class CoSecAutoConfiguration {
    /**
     * The name of every cache CoSec may create ([CoSecCacheSpecs.CACHE_NAMES]), published to CoCache's name check
     * (CoCache 8.0.0-rc.1): a `cocache.caches.[name]` entry for one this instance does not create, such as a
     * control-plane cache in a configuration every service shares, does not fail startup as an undeclared cache. Only
     * these names are relaxed; a misspelled one still fails.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = [CACHE_NAME_CONTRIBUTOR, COSEC_CACHE_SPECS])
    class CacheNames {
        @Bean
        fun cosecCacheNameContributor(): CacheNameContributor = CacheNameContributor { CoSecCacheSpecs.CACHE_NAMES }
    }

    private companion object {
        const val CACHE_NAME_CONTRIBUTOR = "me.ahoo.cocache.spring.CacheNameContributor"
        const val COSEC_CACHE_SPECS = "me.ahoo.cosec.cache.CoSecCacheSpecs"
    }
}
