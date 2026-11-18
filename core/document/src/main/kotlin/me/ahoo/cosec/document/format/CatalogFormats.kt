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
import me.ahoo.cosec.document.format.DocumentFormats.CATALOG
import me.ahoo.cosec.document.format.DocumentFormats.SNAPSHOT_DOCUMENT
import me.ahoo.cosec.document.format.FormatParts.ref

/** An app's permission catalog. */
internal object CatalogFormats {
    val catalogSchema: SchemaFile = SchemaFile(
        name = CATALOG,
        title = "CoSec Catalog Schema",
        description = "An app's permission catalog (RFC 0004 §4.9, RFC 0012 §4): for UIs and generators; an entry with " +
            "`action` also defines the permission.",
        root = ObjectType(listOf(Field("groups", ArrayType(ref(CATALOG, "group")), required = true))),
        definitions = mapOf(
            "group" to ObjectType(
                listOf(
                    Field("name", StringType(examples = listOf("order")), required = true),
                    Field("description", StringType(examples = listOf("Orders"))),
                    Field(
                        "spaced",
                        BooleanType(),
                        description = "Whether a permission written with `action` applies in any space a role holding it " +
                            "applies in; without it, only in the default space (RFC 0012 §4.2).",
                        default = false,
                    ),
                    Field("permissions", ArrayType(ref(CATALOG, "permission")), required = true),
                ),
            ),
            "permission" to ObjectType(
                listOf(
                    Field("id", StringType(examples = listOf("read", "ship")), required = true),
                    Field("name", StringType(examples = listOf("Read"))),
                    Field("description", StringType(examples = listOf("Read orders"))),
                    Field(
                        "action",
                        actionOrActions(),
                        description = "Defines the permission `perm:<app>/<id>` here (RFC 0012 §4): no hand-written " +
                            "policy of that id may exist.",
                    ),
                    Field("resource", resourceOrResources(), description = "Needs `action`. Absent: any resource."),
                ),
            ),
        ),
    )

    private fun actionOrActions(): AnyOf = AnyOf(
        listOf(
            ref(SNAPSHOT_DOCUMENT, "actionPattern"),
            ArrayType(ref(SNAPSHOT_DOCUMENT, "actionPattern"), minItems = 1)
        ),
    )

    private fun resourceOrResources(): AnyOf = AnyOf(
        listOf(ref(SNAPSHOT_DOCUMENT, "resource"), ArrayType(ref(SNAPSHOT_DOCUMENT, "resource"), minItems = 1)),
    )
}
