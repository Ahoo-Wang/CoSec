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

/** What only the platform document holds: the condition limits and the external attributes. */
internal object PlatformFormats {
    private fun limit(name: String, default: Int, description: String) =
        Field(name, IntegerType(minimum = 1), description = description, default = default)

    /** RFC 0007 §3.1 N3: what every document compiles against, written once in the platform document. */
    internal val platformSections = listOf(
        Field(
            "limits",
            ObjectType(
                listOf(
                    limit("maxNodes", 64, "Nodes of one condition."),
                    limit("maxDepth", 8, "Depth of one condition."),
                    limit("maxInValues", 1024, "Literals of one `in`."),
                    limit("maxCidrBlocks", 256, "Blocks of one `cidr`."),
                    limit("maxLiteralBytes", 1024, "UTF-8 bytes of one literal."),
                    limit("maxCost", 256, "Weighted cost of one statement's condition."),
                ),
            ),
            description = "The condition limits of every document (RFC 0005 §3.5); absent ones are the defaults. Platform only.",
        ),
        Field(
            "attributes",
            ArrayType(
                ObjectType(
                    listOf(
                        Field(
                            "path",
                            StringType(
                                pattern = EXTERNAL_PATH,
                                examples = listOf("ext.geo.region", "ext.risk.score"),
                                counterExamples = listOf("geo.region", "ext.geo", "ext..x", "ext.geo."),
                            ),
                            required = true,
                        ),
                        Field("type", StringType(enum = listOf("string", "bool", "stringSet", "ip")), required = true),
                    ),
                ),
            ),
            description = "The external attributes documents may read (RFC 0006 §5.4.1), with their types; paths unique. Platform only.",
        ),
    )

    private const val EXTERNAL_PATH = "^ext\\.[^.]+\\..+$"
}
