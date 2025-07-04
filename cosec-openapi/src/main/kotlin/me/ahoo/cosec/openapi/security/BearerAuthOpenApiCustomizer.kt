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
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import me.ahoo.cosec.api.CoSec
import java.util.function.Consumer

object BearerAuthOpenApiCustomizer : Consumer<OpenAPI> {
    const val BEARER_AUTH_NAME = CoSec.COSEC_PREFIX + "BearerAuth"
    const val BEARER_SCHEME = "bearer"
    override fun accept(openAPI: OpenAPI) {
        openAPI.addSecurityItem(SecurityRequirement().addList(BEARER_AUTH_NAME))
        if (openAPI.components == null) {
            openAPI.components(Components())
        }

        openAPI.components.addSecuritySchemes(
            BEARER_AUTH_NAME,
            SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme(BEARER_SCHEME)
        )
    }
}
