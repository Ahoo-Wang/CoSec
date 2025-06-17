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
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.openapi.generator.OpenAPIAppPermissionGenerator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation

@Endpoint(id = "cosecAppPermissionGenerator")
class CoSecAppPermissionGeneratorEndpoint(private val openAPIProvider: ObjectProvider<OpenAPI>) {
    @ReadOperation
    fun generate(): AppPermission? {
        val openAPI = openAPIProvider.getIfAvailable() ?: return null
        return OpenAPIAppPermissionGenerator.generate(openAPI)
    }
}
