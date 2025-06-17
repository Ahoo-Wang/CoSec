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

package me.ahoo.cosec.openapi.generator

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.info.Info
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class OpenAPIAppPermissionGeneratorTest {

    @Test
    fun generate() {
        val openAPI = OpenAPI().apply {
            info = Info().title("test")
            paths = Paths()
            PathItem()
                .summary("test")
                .get(Operation().tags(listOf("test")).summary("get"))
                .post(Operation().tags(listOf("test")).summary("set"))
                .also {
                    paths.addPathItem("/test", it)
                }
            PathItem()
                .summary("test2")
                .get(Operation().tags(listOf("test2")))
                .also {
                    paths.addPathItem("/test2", it)
                }
        }

        val appPermission = OpenAPIAppPermissionGenerator.generate(openAPI)
        assertThat(appPermission.id, equalTo("test"))
        assertThat(appPermission.groups, hasSize(2))
    }
}
