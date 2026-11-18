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
import me.ahoo.cosec.document.format.DocumentFormats.SNAPSHOT_DOCUMENT
import me.ahoo.cosec.document.format.FormatParts.astCondition
import me.ahoo.cosec.document.format.FormatParts.ref
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.model.decision.RateLimitSpec

/** A rate limit rule and the section that lists them. */
internal object RateLimitFormats {
    internal val rateLimits = Field(
        "rateLimits",
        ArrayType(ref(SNAPSHOT_DOCUMENT, "rateLimit"), maxItems = CompiledDocument.MAX_RATE_LIMIT_RULES),
        description = "Rate limit rules, in order (RFC 0006 §8.1): what a permitted request spends. At most " +
            "${CompiledDocument.MAX_RATE_LIMIT_RULES} (DOC-6): each one a permit matches costs it a call to the limiter.",
    )

    val rateLimit = ObjectType(
        listOf(
            Field(
                "id",
                StringType(
                    pattern = "^[A-Za-z0-9_.-]+$",
                    examples = listOf("login-per-ip", "orders.api"),
                    counterExamples = listOf("", "login per ip", "a/b", "a:b"),
                ),
                required = true,
                description = "Unique in its document. An app's or tenant's rule counts in buckets of its own.",
            ),
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
            Field("condition", astCondition, description = "Which requests count; matched as a DENY is."),
            Field(
                "onMissing",
                StringType(enum = listOf("indeterminate", "nomatch")),
                description = "An external attribute that cannot be fetched: indeterminate leaves it to strictFailure; " +
                    "nomatch skips the rule (platform document only).",
                default = "indeterminate",
            ),
            Field(
                "key",
                StringType(examples = listOf("environment.ip", "subject.id", "resource.var.orderId")),
                description = "The text or address attribute whose value names the bucket. Absent: one bucket; " +
                    "an absent value: the empty bucket.",
            ),
            Field("permitsPerSecond", NumberType(exclusiveMinimum = 0), required = true),
            Field("windowSeconds", IntegerType(minimum = 1, maximum = RateLimitSpec.MAX_WINDOW_SECONDS), default = 1),
            Field(
                "strictFailure",
                BooleanType(),
                description = "Whether a rate limiter that cannot count refuses the request (503) instead of skipping the rule.",
                default = false,
            ),
        ),
        description = "A rate limit rule (RFC 0006 §8): a permitted request it matches spends one permit; an " +
            "exhausted one is 429.",
    )
}
