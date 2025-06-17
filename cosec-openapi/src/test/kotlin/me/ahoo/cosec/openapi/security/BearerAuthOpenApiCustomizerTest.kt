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

package me.ahoo.cosec.openapi.security

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.security.SecurityScheme
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class BearerAuthOpenApiCustomizerTest {
    @Test
    fun customise() {
        val openAPI = OpenAPI()
        BearerAuthOpenApiCustomizer.accept(openAPI)
        openAPI.components.securitySchemes.containsKey(BearerAuthOpenApiCustomizer.BEARER_AUTH_NAME)
        val bearerAuthSecuritySchema = openAPI.components.securitySchemes[BearerAuthOpenApiCustomizer.BEARER_AUTH_NAME]!!
        bearerAuthSecuritySchema.type.assert().isEqualTo(SecurityScheme.Type.HTTP)
        bearerAuthSecuritySchema.scheme.assert().isEqualTo(BearerAuthOpenApiCustomizer.BEARER_SCHEME)
    }

    @Test
    fun customiseWithComponents() {
        val openAPI = OpenAPI()
        openAPI.components(Components())
        BearerAuthOpenApiCustomizer.accept(openAPI)
        openAPI.components.securitySchemes.containsKey(BearerAuthOpenApiCustomizer.BEARER_AUTH_NAME)
    }
}
