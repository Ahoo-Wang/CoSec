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

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.opentelemetry.TracingAuthorization
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@AutoConfiguration
@ConditionalOnCoSecEnabled
@ConditionalOnOpenTelemetryEnabled
class CoSecOpenTelemetryAutoConfiguration {

    @Bean
    @Primary
    fun tracingAuthorization(authorization: Authorization): Authorization {
        return TracingAuthorization(authorization)
    }
}
