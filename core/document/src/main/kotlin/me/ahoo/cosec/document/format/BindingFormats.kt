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
import me.ahoo.cosec.document.format.DocumentFormats.BINDING
import me.ahoo.cosec.document.format.FormatParts.ref

/** A binding, with the platform document's variant of it. */
internal object BindingFormats {
    private val wildcardOrId = { examples: List<String>, minLength: Int? ->
        StringType(minLength = minLength, examples = listOf("*") + examples, description = "An id, or `*` for any.")
    }

    val bindingSchema: SchemaFile = SchemaFile(
        name = BINDING,
        title = "CoSec Binding Schema",
        description = "\"[subject] is subject to [policy] in [app] and [space]\" (RFC 0004 §4.2). `*` means any, and only that.",
        root = ObjectType(
            listOf(
                Field("id", StringType(examples = listOf("deny-ip", "editor-read")), required = true),
                Field(
                    "subject",
                    AnyOf(
                        listOf(
                            StringType(enum = listOf("*"), description = "Anyone, anonymous principals included."),
                            ref(BINDING, "principalSubject"),
                            ref(BINDING, "roleSubject"),
                        ),
                    ),
                    required = true,
                ),
                Field("app", wildcardOrId(listOf("order-service"), 1), required = true),
                Field("space", wildcardOrId(listOf("", "team-a"), null), required = true),
                Field(
                    "policy",
                    StringType(examples = listOf("deny-ip", "perm:order-service/read", "cosec:root")),
                    required = true
                ),
                Field(
                    "expiresAt",
                    StringType(format = "date-time", examples = listOf("2026-12-31T00:00:00Z")),
                    description = "When the binding stops applying. Required for break-glass bindings.",
                ),
                Field(
                    "breakGlass",
                    BooleanType(),
                    description = "A break-glass binding: platform document, platform-tenant principal, expiresAt.",
                    default = false,
                ),
            ),
        ),
        definitions = mapOf(
            "principalSubject" to ObjectType(
                listOf(
                    Field("principal", StringType(examples = listOf("cosec", "svc-batch")), required = true),
                    Field(
                        "tenant",
                        StringType(examples = listOf("(platform)", "acme")),
                        description = "Required in the platform document; a tenant document's own tenant otherwise.",
                    ),
                ),
            ),
            "roleSubject" to ObjectType(
                listOf(
                    Field("role", StringType(examples = listOf("editor", "viewer")), required = true),
                    Field(
                        "tenant",
                        StringType(examples = listOf("acme")),
                        description = "Absent: any tenant in an app document, the document's own in a tenant document.",
                    ),
                ),
            ),
        ),
    )

    /** A binding of the platform document: as [bindingSchema], except that a principal always names its tenant. */
    internal fun platformBinding(): ObjectType {
        val root = bindingSchema.root as ObjectType
        val principal = ObjectType(
            listOf(
                Field("principal", StringType(examples = listOf("cosec", "svc-batch")), required = true),
                Field("tenant", StringType(examples = listOf("(platform)", "acme")), required = true),
            ),
        )
        return ObjectType(
            root.fields.map { field ->
                if (field.name != "subject") {
                    field
                } else {
                    Field(
                        "subject",
                        AnyOf(
                            listOf(
                                StringType(enum = listOf("*"), description = "Anyone, anonymous principals included."),
                                principal,
                                ref(BINDING, "roleSubject"),
                            ),
                        ),
                        required = true,
                    )
                }
            },
            description = "A binding of the platform document: a principal names its tenant.",
        )
    }
}
