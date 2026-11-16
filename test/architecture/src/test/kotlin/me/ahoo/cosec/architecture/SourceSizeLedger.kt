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

package me.ahoo.cosec.architecture

/**
 * RFC 0013 §8.1: the main source files allowed over [LIMIT] lines, each with its cap and what becomes of it. A cap is
 * the file's size when it was recorded; [SourceSizeTest] fails when a file outgrows it, and when the file shrinks more
 * than 10 % below it, so every shrink is written down here. An entry is reviewed like code: a new one, or a raised cap,
 * needs a reason a reviewer accepts.
 */
object SourceSizeLedger {
    /** The most lines a main source file has without an entry. */
    const val LIMIT = 400

    /** How far below its cap a file may shrink before the cap must follow: 10 %. */
    const val SLACK = 0.10

    class Entry(val path: String, val cap: Int, val disposition: Disposition)

    val ENTRIES: List<Entry> = listOf(
        Entry(
            "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/snapshot/SnapshotSource.kt",
            // +3 (SNP-13): what the futures of revalidate and recompile promise, in their KDoc.
            // +3 (B2b gate 1): the test seam for housekeeping, a parameter with its KDoc and its import.
            450,
            Disposition.Stays(
                "the read interface with its contract, and the construction and close order in one place"
            ),
        ),
        Entry(
            "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/snapshot/Slot.kt",
            // +24 (SNP-14): the claimed kind of a removal, the counts moved where an entry is installed, an install over
            // the cap evicting before it answers; the code B2b rewrites.
            781,
            Disposition.Debt(Batch.B2b),
        ),
        Entry(
            "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/Converter.kt",
            918,
            Disposition.Stays("migrate's one-off conversion of 7.x data, run once per deployment"),
        ),
        Entry(
            "core/control/src/main/kotlin/me/ahoo/cosec/control/lifecycle/ChangeChecks.kt",
            499,
            Disposition.Debt(Batch.B4),
        ),
        Entry(
            "core/control/src/main/kotlin/me/ahoo/cosec/control/change/ChangeRequestCodec.kt",
            528,
            Disposition.Debt(Batch.B4),
        ),
        Entry(
            "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/PartTranslation.kt",
            500,
            Disposition.Stays("migrate's one-off translation of 7.x condition parts"),
        ),
        Entry(
            "core/control/src/main/kotlin/me/ahoo/cosec/control/api/DefaultControlPlane.kt",
            454,
            Disposition.Debt(Batch.B4),
        ),
        Entry(
            "core/model/src/main/kotlin/me/ahoo/cosec/model/condition/ConditionChecker.kt",
            403,
            Disposition.Stays("a checker, one function per operator of a closed condition AST"),
        ),
    )
}
