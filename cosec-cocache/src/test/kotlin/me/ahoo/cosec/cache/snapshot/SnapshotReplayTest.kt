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

package me.ahoo.cosec.cache.snapshot

import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.parity.AuthorizationFactory
import me.ahoo.cosec.parity.DecisionReplay
import me.ahoo.cosec.parity.FailureView
import me.ahoo.cosec.parity.REPLAY_DIFFERENCES
import me.ahoo.cosec.parity.REPLAY_GATE_NOT_MET
import me.ahoo.cosec.parity.REPLAY_INVALID_INPUT
import me.ahoo.cosec.parity.ReplayGate
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * RFC 0003 §8.2: the replay with the snapshot read path as the candidate, end to end on the synthetic export of
 * `cosec-core`'s test fixtures (no real data). The gate is RFC 0002 §8.3's, unchanged.
 */
class SnapshotReplayTest {
    private fun export(dir: Path): Path {
        FILES.forEach { file ->
            javaClass.getResourceAsStream("/cosec-replay/$file")!!.use { Files.copy(it, dir.resolve(file)) }
        }
        return dir
    }

    private fun runMain(dir: Path, candidate: SnapshotReplaySide = SnapshotReplaySide()): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = SnapshotReplay.run(arrayOf(dir.toString(), *LENIENT_ARGS), PrintStream(out, true), candidate)
        return code to out.toString()
    }

    @Test
    fun replaysTheSyntheticExportThroughTheSnapshotPath(@TempDir dir: Path) {
        val report = DecisionReplay(
            AuthorizationFactory.ENGINE,
            SnapshotReplaySide(),
            LENIENT,
            FailureView.INNERMOST_CAUSE,
        ).run(export(dir))

        report.samples.assert().isEqualTo(16L)
        report.same.assert().isEqualTo(16L)
        report.differences.assert().isEqualTo(0L)
        // A missing principal policy and a grant of an undefined permission fail closed on both sides, alike.
        report.bothFailed.assert().isEqualTo(2L)
        report.candidateErrors.assert().isEqualTo(report.baselineErrors)
        report.byLayer.assert().isEqualTo(
            mapOf(
                "BLACKLIST" to 1L,
                "ERROR" to 2L,
                "GLOBAL_POLICY" to 3L,
                "NONE" to 2L,
                "PRINCIPAL_POLICY" to 5L,
                "ROLE_PERMISSION" to 2L,
                "ROOT" to 1L,
            ),
        )
        report.recordedAgreement.assert().isEqualTo(14L)
        report.failureView.assert().isEqualTo(FailureView.INNERMOST_CAUSE)
        report.gateUnmet.assert().isEmpty()
        // Met with lenient thresholds only: never evidence.
        report.evidence.assert().isFalse()
        Files.readAllLines(dir.resolve(DecisionReplay.MISMATCHES_FILE)).assert().isEmpty()

        val (code, out) = runMain(dir)
        code.assert().isEqualTo(REPLAY_GATE_NOT_MET)
        out.assert().contains("same=16 mismatch=0 candidate_error=0").contains("failures compared INNERMOST_CAUSE")
    }

    @Test
    fun anInjectedDifferenceShowsAsAMismatch(@TempDir dir: Path) {
        // The published global deny of /admin/** turns into an allow: only the snapshot side sees it.
        val tampered = SnapshotReplaySide { publisher ->
            val allowAdmin = CoSecJsonSerializer.readValue(
                """{"id":"no-admin","name":"No admin","type":"global","tenantId":"t1",
                   "statements":[{"name":"deny-admin","effect":"allow","action":"/admin/**"}]}""",
                Policy::class.java,
            )
            publisher.setPolicy(allowAdmin).block()
        }
        val (code, out) = runMain(export(dir), tampered)

        code.assert().isEqualTo(REPLAY_DIFFERENCES)
        out.assert().contains("DIFFERENCES: 1 mismatches")
        val mismatches = Files.readAllLines(dir.resolve(DecisionReplay.MISMATCHES_FILE))
            .map { CoSecJsonSerializer.readTree(it) }
        mismatches.map { it.get("verdict").asString() }.assert().containsExactly("MISMATCH")
        val mismatch = mismatches.single()
        mismatch.get("event").get("request").get("path").asString().assert().isEqualTo("/admin/users")
        mismatch.get("baseline").get("status").asString().assert().isEqualTo("UNAUTHORIZED")
        mismatch.get("candidate").get("status").asString().assert().isEqualTo("ALLOWED")
    }

    @Test
    fun aReplayThatDecidesNothingDoesNotPass(@TempDir dir: Path) {
        // No policies and no apps: both sides deny everything implicitly and agree, without deciding anything.
        Files.writeString(dir.resolve(DecisionReplay.POLICIES_FILE), "[]")
        Files.writeString(dir.resolve(DecisionReplay.APP_ROLE_PERMISSIONS_FILE), "[]")
        roleAllows(dir, days = 8, count = 3)
        val report = DecisionReplay(
            AuthorizationFactory.ENGINE,
            SnapshotReplaySide(),
            ReplayGate(minSamples = 1, minDays = 1.0, minDistinctDays = 1),
            FailureView.INNERMOST_CAUSE,
        ).run(dir)
        report.same.assert().isEqualTo(24L)
        report.differences.assert().isEqualTo(0L)
        report.gateUnmet.joinToString().assert()
            .contains("0 of 24 compared samples agree with the recorded decision")
            .contains("24 samples name apps [billing] that app-role-permissions.json does not export")
        report.evidence.assert().isFalse()

        val out = ByteArrayOutputStream()
        SnapshotReplay.run(arrayOf(dir.toString()), PrintStream(out, true)).assert().isEqualTo(REPLAY_GATE_NOT_MET)
        out.toString().assert().contains("GATE NOT MET")
    }

    @Test
    fun anExportTheReaderRefusesIsNotReplayable(@TempDir dir: Path) {
        export(dir)
        // Grants of an app without an app permission: 7.0 ignores them, a document has no place for them (§4.7).
        Files.writeString(
            dir.resolve(DecisionReplay.APP_ROLE_PERMISSIONS_FILE),
            """[{ "appId": "billing", "grants": { "admin": ["*"] } }]""",
        )
        val (code, out) = runMain(dir)
        code.assert().isEqualTo(REPLAY_INVALID_INPUT)
        out.assert().contains("cannot be converted to snapshot documents")
            .contains("app [billing] has grants [billing:admin] but no app permission")
        Files.exists(dir.resolve(DecisionReplay.REPORT_FILE)).assert().isFalse()
    }

    /** [count] samples a day over [days] days, all recorded as allowed by a role permission of app `billing`. */
    private fun roleAllows(dir: Path, days: Int, count: Int) {
        val template = javaClass.getResourceAsStream("/cosec-replay/${DecisionReplay.REQUESTS_FILE}")!!
            .bufferedReader().readLines().single { "\"eventId\":\"e10\"" in it }
        val lines = (0 until days).flatMap { day ->
            (0 until count).map { index ->
                template.replace("\"e10\"", "\"d$day-$index\"")
                    .replace(
                        "2026-10-02T12:00:00Z",
                        Instant.parse("2026-10-01T12:00:00Z").plusSeconds(day * 86_400L).toString()
                    )
            }
        }
        Files.write(dir.resolve(DecisionReplay.REQUESTS_FILE), lines)
    }

    companion object {
        private val FILES = listOf(
            DecisionReplay.POLICIES_FILE,
            DecisionReplay.APP_ROLE_PERMISSIONS_FILE,
            DecisionReplay.REQUESTS_FILE,
            DecisionReplay.CONFIG_FILE,
        )

        /** For the 16-sample synthetic export, which can never meet the real gate. */
        private val LENIENT = ReplayGate(
            minSamples = 10,
            minDays = 1.0,
            minDistinctDays = 1,
            maxBothFailedRate = 0.2,
            minRecordedAgreementRate = 0.5,
        )
        private val LENIENT_ARGS = arrayOf(
            "--min-samples=10",
            "--min-days=1",
            "--min-distinct-days=1",
            "--max-both-failed-rate=0.2",
            "--min-agreement-rate=0.5",
        )
    }
}
