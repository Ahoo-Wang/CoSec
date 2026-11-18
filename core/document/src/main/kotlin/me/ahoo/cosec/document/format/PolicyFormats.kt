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
import me.ahoo.cosec.document.format.DocumentFormats.DEFINITIONS
import me.ahoo.cosec.document.format.DocumentFormats.SNAPSHOT_DOCUMENT
import me.ahoo.cosec.document.format.FormatParts.actor
import me.ahoo.cosec.document.format.FormatParts.astCondition
import me.ahoo.cosec.document.format.FormatParts.ref
import me.ahoo.cosec.engine.pattern.ResourcePattern

/** A policy and its statements, and the action and resource patterns they are written with. */
internal object PolicyFormats {
    private val effect = ref(DEFINITIONS, "effect")

    val policy = ObjectType(
        listOf(
            Field("id", StringType(examples = listOf("deny-ip", "perm:order-service/read")), required = true),
            Field("attachable", BooleanType(), description = "Whether a principal may attach it.", default = false),
            Field(
                "boundary",
                BooleanType(),
                description = "A platform boundary (RFC 0007 §7.6): in the platform document only, at most 64 ALLOW " +
                    "statements, never bound or attached.",
                default = false,
            ),
            Field("condition", astCondition, description = "The policy condition; it cannot read captured variables."),
            Field("statements", ArrayType(ref(SNAPSHOT_DOCUMENT, "statement")), required = true),
        ),
    )

    val statement = ObjectType(
        listOf(
            Field("name", StringType(examples = listOf("read-orders"))),
            Field("effect", effect, required = true),
            Field(
                "action",
                AnyOf(
                    listOf(
                        ref(SNAPSHOT_DOCUMENT, "actionPattern"),
                        ArrayType(ref(SNAPSHOT_DOCUMENT, "actionPattern"), minItems = 1)
                    )
                ),
                required = true,
            ),
            Field(
                "resource",
                AnyOf(
                    listOf(
                        ref(SNAPSHOT_DOCUMENT, "resource"),
                        ArrayType(ref(SNAPSHOT_DOCUMENT, "resource"), minItems = 1)
                    )
                ),
                description = "Absent: any resource.",
            ),
            Field("condition", astCondition),
            Field(
                "onMissing",
                StringType(enum = listOf("indeterminate", "nomatch")),
                description = "What an external attribute (ext.*) the condition reads that cannot be fetched means " +
                    "(RFC 0006 §5.4.3). Only for a condition that reads one; a DENY that says nomatch needs " +
                    "onMissingConfirmed.",
                default = "indeterminate",
            ),
            Field(
                "onMissingConfirmed",
                ObjectType(
                    listOf(
                        Field("by", actor, required = true),
                        Field("change", StringType(minLength = 1, examples = listOf("0QH8K3")), required = true),
                    ),
                ),
                description = "Who confirmed that this DENY with onMissing: nomatch may lapse while its attributes " +
                    "cannot be fetched, and in which change (RFC 0007 §9.5). The control plane writes it.",
            ),
        ),
    )

    val actionPattern = StringType(
        pattern = FormatPatterns.ACTION_PATTERN,
        examples = listOf("*", "order:*", "order:read", "http:GET", "http:*"),
        counterExamples = FormatPatterns.ACTION_PATTERN_COUNTER_EXAMPLES,
        description = "`service:verb`, `service:*` or `*` (RFC 0005 §5.1). No `http` action no request can have " +
            "(`DOC-8`): not `http:HEAD`, which is decided as `http:GET`, and no verb with a lower-case letter.",
    )

    val resource = AnyOf(
        listOf(
            ref(SNAPSHOT_DOCUMENT, "resourcePattern"),
            ObjectType(
                listOf(
                    Field("pattern", ref(SNAPSHOT_DOCUMENT, "resourcePattern"), required = true),
                    Field("caseSensitive", BooleanType(), default = true),
                    Field("decodeAndParseSegments", BooleanType(), default = true),
                ),
                description = "An `http:path:` pattern with options.",
            ),
        ),
    )

    val resourcePattern = StringType(
        pattern = FormatPatterns.RESOURCE_PATTERN,
        maxLength = ResourcePattern.MAX_LENGTH,
        examples = listOf("order/{orderId}", "order/**", "http:path:/orders/{id}", "http:path:/admin/**"),
        counterExamples = FormatPatterns.RESOURCE_PATTERN_COUNTER_EXAMPLES,
        description = "`type/segment/...` or `http:path:<pattern>` (RFC 0005 §5.1, §5.2). A DENY cannot name a bare type.",
    )
}
