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

package me.ahoo.cosec.spring.boot.actuate

import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.document.route.RouteMapCodec
import me.ahoo.cosec.openapi.generator.OpenAPIServiceGenerator
import org.springdoc.api.AbstractOpenApiResource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.Selector
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.util.ReflectionUtils
import tools.jackson.databind.JsonNode
import java.lang.reflect.Method
import java.util.Locale

@AutoConfiguration
@ConditionalOnClass(OpenAPIServiceGenerator::class, Endpoint::class, AbstractOpenApiResource::class)
internal class CoSecServiceGeneratorEndpointAutoConfiguration {
    @Bean
    fun cosecServiceGeneratorEndpoint(
        springDoc: ObjectProvider<AbstractOpenApiResource>,
        environment: Environment,
    ): CoSecServiceGeneratorEndpoint =
        CoSecServiceGeneratorEndpoint(springDoc, environment)
}
