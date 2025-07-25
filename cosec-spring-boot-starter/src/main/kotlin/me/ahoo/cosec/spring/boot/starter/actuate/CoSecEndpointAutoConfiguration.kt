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
package me.ahoo.cosec.spring.boot.starter.actuate

import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.cosec.openapi.generator.OpenAPIPolicyGenerator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean

/**
 * CoSec Endpoint AutoConfiguration .
 *
 * @author ahoo wang
 */

@AutoConfiguration
@ConditionalOnClass(
    value = [OpenAPIPolicyGenerator::class, Endpoint::class]
)
class CoSecEndpointAutoConfiguration {
    @Bean
    @ConditionalOnClass(value = [OpenAPI::class])
    fun coSecPolicyGeneratorEndpoint(openAPIProvider: ObjectProvider<OpenAPI>): CoSecPolicyGeneratorEndpoint {
        return CoSecPolicyGeneratorEndpoint(openAPIProvider)
    }

    @Bean
    @ConditionalOnClass(value = [OpenAPI::class])
    fun coSecAppPermissionGeneratorEndpoint(
        openAPIProvider: ObjectProvider<OpenAPI>
    ): CoSecAppPermissionGeneratorEndpoint {
        return CoSecAppPermissionGeneratorEndpoint(openAPIProvider)
    }
}
