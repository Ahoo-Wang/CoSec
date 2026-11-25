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

package me.ahoo.cosec.spring.boot.realserver

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spring.boot.pipeline.PipelineProperties
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * A host's own environment post-processor that puts it in `gateway` mode, as the first application upgraded to
 * 8.0.0-beta.9 does: unordered, so it runs after every ordered one, CoSec's own then included, and added last, below
 * every other property source (`IDN-43`). Registered for the tests in `META-INF/spring.factories`, and inert unless the
 * application is started with [SWITCH] true.
 */
class LateGatewayMode : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (environment.getProperty(SWITCH) != "true") return
        environment.propertySources.addLast(MapPropertySource(SOURCE, mapOf(PipelineProperties.MODE_KEY to "gateway")))
    }

    companion object {
        /** Outside `cosec.*`, which the starter refuses unknown keys of. */
        const val SWITCH = "test.late-gateway-mode"
        const val SOURCE = "lateGatewayMode"
    }
}
