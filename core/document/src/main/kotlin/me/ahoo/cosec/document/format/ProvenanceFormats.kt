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
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.document.format.FormatParts.actor
import me.ahoo.cosec.spi.snapshot.SnapshotDigest

/** A revision's provenance. */
internal object ProvenanceFormats {
    private const val DIGEST = SnapshotDigest.PATTERN

    private const val DIGEST_HEX = "9f2c0b1e6a6d1f2c3b4a5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809102"

    val provenance = ObjectType(
        listOf(
            Field(
                "parent",
                ObjectType(
                    listOf(
                        Field("revision", IntegerType(minimum = 1), required = true),
                        Field(
                            "digest",
                            StringType(
                                pattern = DIGEST,
                                examples = listOf("SHA-256:$DIGEST_HEX"),
                                counterExamples = listOf(
                                    "SHA-256:${DIGEST_HEX.uppercase()}",
                                    // 40 hex digits (a SHA-1 length), without an algorithm prefix.
                                    "9f2c0b1e6a6d1f2c3b4a5d6e7f8091a2b3c4d5e6",
                                    DIGEST_HEX,
                                    "SHA-1:9f2c0b1e6a6d1f2c3b4a5d6e7f8091a2b3c4d5e6",
                                    "SHA-256:${DIGEST_HEX}a",
                                    "",
                                ),
                            ),
                            required = true,
                            description = "The store's SHA-256 digest of the parent, with its algorithm: the address of its revision record.",
                        ),
                    ),
                ),
                description = "The head this revision replaced; absent for the first revision.",
            ),
            Field("change", StringType(minLength = 1, examples = listOf("0QH8K3")), required = true),
            Field(
                "kind",
                StringType(enum = ProvenanceKind.entries.map { it.name }),
                required = true,
            ),
            Field("publishedBy", actor, required = true),
            Field("approvedBy", ArrayType(actor), description = "Absent or empty without an approval."),
            Field(
                "shadow",
                ObjectType(
                    listOf(
                        Field("window", StringType(minLength = 1, examples = listOf("0QH8M1"))),
                        Field("skipped", BooleanType(), required = true),
                    ),
                ),
            ),
        ),
        description = "The control plane's record of how a revision was published; it decides nothing.",
    )
}
