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

import me.ahoo.cosec.annotations.InternalCoSecApi
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.LazyInitializationExcludeFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * What the starter runs at startup whether or not anything asks for it (`STA-3`): its checks, and the components that
 * keep watching once the host runs (the eviction policy monitor, shadow reporting, control-plane maintenance, the
 * listeners' metrics). No bean depends on them, so under `spring.main.lazy-initialization=true` Spring would never
 * create them: a host missing its web adapter would start with no authorization, a gateway in `gateway` mode would
 * forward every request unchecked, and Redis could evict the policy snapshot unseen. [EAGER] keeps every bean whose type
 * is a [StartupCheck] out of lazy initialization; a `@Bean` method declares one as its return type, never a bare
 * [InitializingBean] or [SmartInitializingSingleton], and its auto-configuration imports [StartupCheckExclusion], which
 * `StartupCheckTest` holds every auto-configuration to.
 */
@InternalCoSecApi
interface StartupCheck {
    /** A check that runs as its bean is created: before the beans that would fail on what it reports. */
    @InternalCoSecApi
    fun interface OnCreation : StartupCheck, InitializingBean

    /** A check that runs once every singleton exists: one that reads beans, such as the exposed endpoints. */
    @InternalCoSecApi
    fun interface AfterSingletons : StartupCheck, SmartInitializingSingleton

    @InternalCoSecApi
    companion object {
        /** Every [StartupCheck] bean is created at startup, lazy initialization or not. */
        val EAGER: LazyInitializationExcludeFilter = LazyInitializationExcludeFilter.forBeanTypes(
            StartupCheck::class.java
        )
    }
}

/**
 * Registers [StartupCheck.EAGER]. Every auto-configuration that declares a [StartupCheck] imports it, so the exclusion
 * is there wherever a check is, whichever auto-configurations an application excludes; imported many times, it is one
 * bean.
 */
// A class, not an object: Spring instantiates what it imports.
@Suppress("UtilityClassWithPublicConstructor")
@Configuration(proxyBeanMethods = false)
@InternalCoSecApi
class StartupCheckExclusion {
    @InternalCoSecApi
    companion object {
        /** Static: `LazyInitializationBeanFactoryPostProcessor` reads it before any configuration is instantiated. */
        @JvmStatic
        @Bean
        fun cosecStartupChecksEager(): LazyInitializationExcludeFilter = StartupCheck.EAGER
    }
}
