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

package me.ahoo.cosec.document.format

import me.ahoo.cosec.annotations.InternalCoSecApi

/** A system role and a custom one. */
internal object RoleFormats {
    private val roleFields = listOf(
        Field(
            "id",
            StringType(
                pattern = "^(?!\\*$)[^@]+$",
                examples = listOf("wms.clerk", "night-shift"),
                counterExamples = listOf("", "*", "clerk@wh-01"),
            ),
            required = true,
            description = "Unique in its document. Not `*`, and without `@`, which separates a membership's role and space.",
        ),
        Field("name", StringType(examples = listOf("Clerk")), description = "For display only."),
        Field("description", StringType(examples = listOf("Handles the warehouse")), description = "For display only."),
    )

    private val permissionIds = ArrayType(StringType(examples = listOf("warehouse.read", "inbound.ship")))

    internal val appRole = ObjectType(
        roleFields + Field(
            "permissions",
            AnyOf(listOf(StringType(enum = listOf("*"), description = "Every permission the catalog defines."), permissionIds)),
            required = true,
            description = "Catalog ids; possibly none.",
        ),
        description = "A system role (RFC 0012 §5.1): it applies to every tenant, in any space a member holds it.",
    )

    internal val tenantRole = ObjectType(
        roleFields + listOf(
            Field(
                "app",
                StringType(minLength = 1, examples = listOf("wms", "order-service")),
                required = true,
                description = "The app the role belongs to, and whose permissions it lists.",
            ),
            Field(
                "space",
                StringType(
                    pattern = "^(?!\\*$)(?!\\().*$",
                    examples = listOf("", "wh-01"),
                    counterExamples = listOf("*", "(reserved)"),
                ),
                description = "Absent: any space a member holds the role in. Otherwise only this one; empty is the default space.",
            ),
            Field("permissions", permissionIds, required = true, description = "Catalog ids of the app; possibly none."),
        ),
        description = "A custom role (RFC 0012 §5.2): it applies to this tenant only.",
    )
}
