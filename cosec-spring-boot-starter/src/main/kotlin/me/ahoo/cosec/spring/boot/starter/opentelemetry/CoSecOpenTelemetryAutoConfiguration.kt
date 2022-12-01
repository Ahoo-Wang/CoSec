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

package me.ahoo.cosec.spring.boot.starter.opentelemetry

import me.ahoo.cosec.opentelemetry.gateway.TraceGatewayFilter
import me.ahoo.cosec.opentelemetry.webflux.TraceWebFilter
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@AutoConfiguration
@ConditionalOnCoSecEnabled
@ConditionalOnOpenTelemetryEnabled
class CoSecOpenTelemetryAutoConfiguration {

    @Configuration
    @ConditionalOnMissingClass("org.springframework.cloud.gateway.filter.GlobalFilter")
    class Webflux {
        @Bean
        fun traceWebFilter(): TraceWebFilter {
            return TraceWebFilter
        }
    }
    @Configuration
    @ConditionalOnClass(name = ["org.springframework.cloud.gateway.filter.GlobalFilter"])
    class Gateway {
        @Bean
        fun traceGatewayFilter(): TraceGatewayFilter {
            return TraceGatewayFilter
        }
    }
}
