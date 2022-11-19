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

package me.ahoo.cosec.spring.boot.starter

import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

internal class CoSecAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .withUserConfiguration(CoSecAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                assertThat(context)
                    .hasSingleBean(CoSecProperties::class.java)
                    .hasSingleBean(CoSecAutoConfiguration::class.java)
            }
    }

    @Test
    fun contextLoadsWhenDisable() {
        contextRunner
            .withPropertyValues("${ConditionalOnCoSecEnabled.ENABLED_KEY}=false")
            .withUserConfiguration(CoSecAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                assertThat(context)
                    .doesNotHaveBean(CoSecProperties::class.java)
                    .doesNotHaveBean(CoSecAutoConfiguration::class.java)
            }
    }
}
