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
            1672,
            Disposition.Debt(Batch.B2a),
        ),
        Entry(
            "core/engine/src/main/kotlin/me/ahoo/cosec/engine/flat/FlatEvaluator.kt",
            971,
            Disposition.Debt(Batch.B3),
        ),
        Entry(
            "core/control/src/main/kotlin/me/ahoo/cosec/control/change/Changes.kt",
            938,
            Disposition.Debt(Batch.B4),
        ),
        Entry(
            "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/Converter.kt",
            918,
            Disposition.Stays("migrate's one-off conversion of 7.x data, run once per deployment"),
        ),
        Entry(
            "core/control/src/main/kotlin/me/ahoo/cosec/control/api/ControlHttp.kt",
            778,
            Disposition.Debt(Batch.B4),
        ),
        Entry(
            "core/control/src/main/kotlin/me/ahoo/cosec/control/change/ChangeAuthority.kt",
            // +1 (IDN-22): a recorded approver's principal names its session, which a recorded one never has.
            773,
            Disposition.Debt(Batch.B4),
        ),
        Entry(
            "core/control/src/main/kotlin/me/ahoo/cosec/control/store/Revisions.kt",
            756,
            Disposition.Debt(Batch.B4),
        ),
        Entry(
            "core/document/src/main/kotlin/me/ahoo/cosec/document/PolicyDocumentCodec.kt",
            676,
            Disposition.Debt(Batch.B5),
        ),
        Entry(
            "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/MigrateMain.kt",
            646,
            Disposition.Debt(Batch.B6),
        ),
        Entry(
            "core/document/src/main/kotlin/me/ahoo/cosec/document/format/PolicyDocumentFormats.kt",
            585,
            Disposition.Debt(Batch.B5),
        ),
        Entry(
            "core/control/src/main/kotlin/me/ahoo/cosec/control/grant/GrantAnalyzer.kt",
            583,
            Disposition.Debt(Batch.B4),
        ),
        Entry(
            "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/ApiKeyMigration.kt",
            560,
            Disposition.Debt(Batch.B6),
        ),
        Entry(
            "core/control/src/main/kotlin/me/ahoo/cosec/control/change/ChangeChecks.kt",
            552,
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
            "core/engine/src/main/kotlin/me/ahoo/cosec/engine/flat/CompiledDocument.kt",
            488,
            Disposition.Debt(Batch.B3),
        ),
        Entry(
            "core/engine/src/main/kotlin/me/ahoo/cosec/engine/pattern/HttpPathPatternParser.kt",
            486,
            Disposition.Stays("a grammar, one function per production"),
        ),
        Entry(
            "core/document/src/main/kotlin/me/ahoo/cosec/document/PolicyDocumentCompiler.kt",
            466,
            Disposition.Debt(Batch.B5),
        ),
        Entry(
            "core/control/src/main/kotlin/me/ahoo/cosec/control/api/DefaultControlPlane.kt",
            464,
            Disposition.Debt(Batch.B4),
        ),
        Entry(
            "host/spring-boot/src/main/kotlin/me/ahoo/cosec/spring/boot/control/CoSecControlAutoConfiguration.kt",
            454,
            Disposition.Debt(Batch.B7),
        ),
        Entry(
            "core/model/src/main/kotlin/me/ahoo/cosec/model/condition/ConditionChecker.kt",
            444,
            Disposition.Stays("a checker, one function per operator of a closed condition AST"),
        ),
        Entry(
            "backends/cocache/src/main/kotlin/me/ahoo/cosec/cocache/CoSecCacheSpecs.kt",
            438,
            Disposition.Debt(Batch.B7),
        ),
        Entry(
            "backends/cocache/src/main/kotlin/me/ahoo/cosec/cocache/control/CoCacheControlStore.kt",
            430,
            Disposition.Debt(Batch.B7),
        ),
        Entry(
            "core/control/src/main/kotlin/me/ahoo/cosec/control/api/Simulator.kt",
            415,
            Disposition.Debt(Batch.B4),
        ),
    )
}
