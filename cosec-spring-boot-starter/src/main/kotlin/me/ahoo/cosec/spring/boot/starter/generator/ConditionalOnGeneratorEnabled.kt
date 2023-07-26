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
package me.ahoo.cosec.spring.boot.starter.generator

import me.ahoo.cosec.generator.PolicyGenerator
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass

/**
 * Conditional On Generator Enabled.
 *
 * @author ahoo wang
 */
@ConditionalOnClass(
    value = [PolicyGenerator::class, Endpoint::class],
    name = [
        "io.swagger.v3.oas.models.OpenAPI"
    ],
)
annotation class ConditionalOnGeneratorEnabled
