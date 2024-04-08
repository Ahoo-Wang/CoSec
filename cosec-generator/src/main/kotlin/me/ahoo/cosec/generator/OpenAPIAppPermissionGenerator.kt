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

package me.ahoo.cosec.generator

import io.swagger.v3.oas.models.OpenAPI
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.Permission
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.policy.action.ACTION_MATCHER_METHOD_KEY
import me.ahoo.cosec.policy.action.PathActionMatcherFactory

object OpenAPIAppPermissionGenerator {
    private const val APP_ID = "AppId"

    fun generate(openAPI: OpenAPI): AppPermission {
        val tagGroupOperations = mutableMapOf<String, MutableList<Permission>>()

        for ((path, item) in openAPI.paths) {
            for ((method, operation) in item.readOperationsMap()) {
                val action = PathActionMatcherFactory.INSTANCE.create(
                    mapOf<String, String>(
                        PathActionMatcherFactory.PATTERN_KEY to path,
                        ACTION_MATCHER_METHOD_KEY to method.name
                    ).asConfiguration()
                )
                val permission = PermissionData(
                    id = operation.operationId.orEmpty(),
                    name = operation.summary.orEmpty(),
                    description = operation.description.orEmpty(),
                    action = action
                )
                for (tag in operation.tags.orEmpty()) {
                    val grouped = tagGroupOperations[tag]
                    if (grouped == null) {
                        tagGroupOperations[tag] = mutableListOf(permission)
                    } else {
                        grouped.add(permission)
                    }
                }
            }
        }

        val groups = tagGroupOperations.map { (tag, permissions) ->
            PermissionGroupData(
                name = tag,
                description = "",
                permissions = permissions
            )
        }
        val appId = openAPI.info?.title ?: APP_ID
        return AppPermissionData(
            id = appId,
            groups = groups,
        )
    }
}
