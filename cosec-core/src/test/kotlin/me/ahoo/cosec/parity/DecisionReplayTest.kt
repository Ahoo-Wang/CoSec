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

package me.ahoo.cosec.parity

import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.authorization.MissingPolicyException
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.policy.condition.ConditionMatcherFactoryProvider
import me.ahoo.cosec.policy.condition.TimeConditionMatcherFactory
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reactor.core.publisher.Mono
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/**
 * RFC 0002 §8.2.1: the replay tool, end to end on the synthetic export under `cosec-replay/` (no real data).
 */
class DecisionReplayTest {
    private fun export(dir: Path, files: List<String> = FILES): Path {
        files.forEach { file ->
            javaClass.getResourceAsStream("/cosec-replay/$file")!!.use { Files.copy(it, dir.resolve(file)) }
        }
        return dir
    }

    private fun sha256(file: Path): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))

    private fun answer(answer: (Request, SecurityContext) -> Mono<AuthorizationDecision>) = answer

    /** Answers like the engine, except on the samples [answers] covers, keyed by request id. */
    private fun engineExcept(answers: Map<String, (Request, SecurityContext) -> Mono<AuthorizationDecision>>) =
        AuthorizationFactory { policyRepository, appRolePermissionRepository, blacklist, root ->
            val engine = AuthorizationFactory.ENGINE.create(
                policyRepository,
                appRolePermissionRepository,
                blacklist,
                root
            )
            object : DecisionAuthorization {
                override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> =
                    answers[request.requestId]?.invoke(request, context) ?: engine.decide(request, context)
            }
        }

    private fun runMain(vararg args: String, candidate: AuthorizationFactory = AuthorizationFactory.ENGINE): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val code = replayMain(arrayOf(*args), PrintStream(out, true), candidate = candidate)
        return code to out.toString()
    }

    @Test
    fun replaysTheSyntheticExport(@TempDir dir: Path) {
        val report = DecisionReplay(gate = LENIENT).run(export(dir))

        report.samples.assert().isEqualTo(16L)
        report.same.assert().isEqualTo(16L)
        report.mismatch.assert().isEqualTo(0L)
        report.candidateError.assert().isEqualTo(0L)
        // A missing principal policy and a grant of an undefined permission fail closed on both sides.
        report.bothFailed.assert().isEqualTo(2L)
        report.failedAlikeForDifferentCauses.assert().isEqualTo(0L)
        report.baselineErrors.assert().isEqualTo(
            mapOf(
                "me.ahoo.cosec.api.permission.MissingPermissionException" to 1L,
                MissingPolicyException::class.java.name to 1L,
            ),
        )
        report.candidateErrors.assert().isEqualTo(report.baselineErrors)
        report.byStatus.assert().isEqualTo(
            mapOf("ALLOWED" to 8L, "ERROR" to 2L, "FORBIDDEN" to 4L, "UNAUTHORIZED" to 2L)
        )
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
        report.from.assert().isEqualTo(Instant.parse("2026-10-01T10:00:00Z"))
        report.to.assert().isEqualTo(Instant.parse("2026-10-03T10:00:00Z"))
        report.spanDays.assert().isEqualTo(2.0)
        // The token-expired sample is not compared; the reports policy changed after its sample was recorded.
        report.recordedCompared.assert().isEqualTo(15L)
        report.recordedAgreement.assert().isEqualTo(14L)
        report.authenticatedInconsistent.assert().isEqualTo(1L)
        report.unrecordedInputs.assert().isEqualTo(
            mapOf(
                "policy:orders" to setOf("principal.attributes", "request.headers"),
                "policy:reports" to setOf("script"),
            ),
        )
        report.supplemented.assert().isEqualTo(mapOf("principal.attributes" to 1L, "request.headers" to 3L))
        report.inputs.keys.assert().containsExactlyInAnyOrderElementsOf(FILES)
        FILES.forEach { report.inputs[it].assert().isEqualTo(sha256(dir.resolve(it))) }
        report.gate.assert().isEqualTo(LENIENT)
        report.gateUnmet.assert().isEmpty()

        val written = CoSecJsonSerializer.readTree(Files.readAllBytes(dir.resolve(DecisionReplay.REPORT_FILE)))
        written.get("samples").asLong().assert().isEqualTo(16L)
        written.get("byLayer").get("ROOT").asLong().assert().isEqualTo(1L)
        written.get("inputs").get(DecisionReplay.POLICIES_FILE).asString().assert()
            .isEqualTo(sha256(dir.resolve(DecisionReplay.POLICIES_FILE)))
        Files.readAllLines(dir.resolve(DecisionReplay.MISMATCHES_FILE)).assert().isEmpty()
        Files.list(
            dir
        ).use { files -> files.noneMatch { it.fileName.toString().endsWith(".partial") }.assert().isTrue() }
        report.summary().assert().contains(
            "same=16 mismatch=0 candidate_error=0"
        ).contains("14 / 15").contains("Gate met (lenient thresholds — not gate evidence)")
    }

    @Test
    fun aLenientGateIsNeverEvidence(@TempDir dir: Path) {
        val report = DecisionReplay(gate = LENIENT).run(export(dir))
        report.gateUnmet.assert().isEmpty()
        report.distinctDays.assert().isEqualTo(3)
        report.duplicateEventIds.assert().isEqualTo(0L)
        report.unexportedAppRequests.assert().isEqualTo(0L)
        report.differences.assert().isEqualTo(0L)
        // Met, but only with thresholds weaker than RFC 0002 §8.3's.
        report.evidence.assert().isFalse()
        exitCodeOf(report).assert().isEqualTo(REPLAY_GATE_NOT_MET)
        exitCodeOf(report.copy(evidence = true)).assert().isEqualTo(REPLAY_OK)
    }

    @Test
    fun theDefaultGateNeedsAMillionSamplesOverAWeek(@TempDir dir: Path) {
        val report = DecisionReplay().run(export(dir))
        report.gate.assert().isEqualTo(ReplayGate())
        report.gateUnmet.assert().hasSize(4)
        report.gateUnmet.joinToString().assert()
            .contains("16 samples, fewer than 1000000")
            .contains("shorter than 7.0")
            .contains("samples on 3 distinct days, fewer than 7")
            .contains("2 of 16 samples failed on both sides")
        report.summary().assert().contains("GATE NOT MET")
        report.evidence.assert().isFalse()
        ReplayGate().meetsRfc().assert().isTrue()
    }

    /** [count] samples a day over [days] days, all recorded as allowed by a role permission of app `billing`. */
    private fun roleAllows(dir: Path, days: Int, count: Int) {
        val template = Files.readAllLines(
            Path.of(javaClass.getResource("/cosec-replay/${DecisionReplay.REQUESTS_FILE}")!!.toURI()),
        ).single { "\"eventId\":\"e10\"" in it }
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

    @Test
    fun anExportThatDecidesNothingFailsTheGate(@TempDir dir: Path) {
        // No policies, no apps: every sample is an implicit deny on both sides, which agrees with itself.
        Files.writeString(dir.resolve(DecisionReplay.POLICIES_FILE), "[]")
        Files.writeString(dir.resolve(DecisionReplay.APP_ROLE_PERMISSIONS_FILE), "[]")
        roleAllows(dir, days = 8, count = 3)
        val report = DecisionReplay().run(dir)
        report.same.assert().isEqualTo(24L)
        report.differences.assert().isEqualTo(0L)
        report.recordedAgreement.assert().isEqualTo(0L)
        report.unexportedAppRequests.assert().isEqualTo(24L)
        report.unexportedApps.assert().containsExactly("billing")
        report.gateUnmet.joinToString().assert()
            .contains("0 of 24 compared samples agree with the recorded decision, fewer than 90.0 %")
            .contains("24 samples name apps [billing] that app-role-permissions.json does not export")
        // Lenient on volume only: the agreement and the unexported app still fail it.
        val volumeOnly = ReplayGate(minSamples = 1, minDays = 1.0, minDistinctDays = 1)
        DecisionReplay(gate = volumeOnly).run(dir).gateUnmet.assert().hasSize(2)
    }

    @Test
    fun anAppExportedWithoutAppPermissionCountsAsExported(@TempDir dir: Path) {
        Files.writeString(dir.resolve(DecisionReplay.POLICIES_FILE), "[]")
        Files.writeString(dir.resolve(DecisionReplay.APP_ROLE_PERMISSIONS_FILE), """[{ "appId": "billing" }]""")
        roleAllows(dir, days = 1, count = 2)
        val report = DecisionReplay().run(dir)
        report.unexportedAppRequests.assert().isEqualTo(0L)
        report.byLayer.assert().isEqualTo(mapOf("NONE" to 2L))

        Files.writeString(dir.resolve(DecisionReplay.APP_ROLE_PERMISSIONS_FILE), """[{ "grants": {} }]""")
        assertThrows(IllegalArgumentException::class.java) { DecisionReplay().run(dir) }.message.assert()
            .contains("neither an appPermission nor an appId")
        Files.writeString(
            dir.resolve(DecisionReplay.APP_ROLE_PERMISSIONS_FILE),
            """[{ "appId": "a" }, { "appId": "a" }]"""
        )
        assertThrows(IllegalArgumentException::class.java) { DecisionReplay().run(dir) }.message.assert()
            .contains("apps [a] more than once")
    }

    @Test
    fun repeatedEventsAreCounted(@TempDir dir: Path) {
        export(dir)
        val requests = dir.resolve(DecisionReplay.REQUESTS_FILE)
        Files.write(requests, Files.readAllLines(requests) + Files.readAllLines(requests).first())
        DecisionReplay(gate = LENIENT).run(dir).duplicateEventIds.assert().isEqualTo(1L)
    }

    @Test
    fun recordedFieldsWinOverExportedHeaders(@TempDir dir: Path) {
        export(dir)
        val requests = dir.resolve(DecisionReplay.REQUESTS_FILE)
        // Sample 10 (app billing) also exports a header naming another app and space.
        Files.write(
            requests,
            Files.readAllLines(requests).map {
                if ("\"eventId\":\"e10\"" !in it) {
                    it
                } else {
                    it.replace(
                        "\"device\":",
                        "\"headers\":{\"${AppIdCapable.APP_ID_KEY}\":\"other\",\"${SpaceIdCapable.SPACE_ID_KEY}\":\"s9\"},\"device\":",
                    )
                }
            },
        )
        val seen = mutableListOf<Pair<String, String>>()
        val recording = engineExcept(emptyMap()).let { engine ->
            AuthorizationFactory { policies, permissions, blacklist, root ->
                val delegate = engine.create(policies, permissions, blacklist, root)
                object : DecisionAuthorization {
                    override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> {
                        if (request.requestId == "r10") {
                            seen += request.appId to request.spaceId
                        }
                        return delegate.decide(request, context)
                    }
                }
            }
        }
        DecisionReplay(candidate = recording, gate = LENIENT).run(dir).differences.assert().isEqualTo(0L)
        seen.assert().containsExactly("billing" to "")
    }

    @Test
    fun thresholdsMustBeValid() {
        assertThrows(IllegalArgumentException::class.java) { ReplayGate(minSamples = 0) }
        assertThrows(IllegalArgumentException::class.java) { ReplayGate(minDays = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ReplayGate(minDays = Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { ReplayGate(minDistinctDays = -1) }
        assertThrows(IllegalArgumentException::class.java) { ReplayGate(maxBothFailedRate = 1.5) }
        assertThrows(IllegalArgumentException::class.java) { ReplayGate(maxBothFailedRate = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ReplayGate(minRecordedAgreementRate = -0.1) }
        listOf(
            ReplayGate(minSamples = 999_999),
            ReplayGate(minDays = 6.9),
            ReplayGate(minDistinctDays = 6),
            ReplayGate(maxBothFailedRate = 0.02),
            ReplayGate(minRecordedAgreementRate = 0.8),
        ).forEach { it.meetsRfc().assert().describedAs("$it").isFalse() }
        ReplayGate(minSamples = 2_000_000, maxBothFailedRate = 0.0, minRecordedAgreementRate = 1.0).meetsRfc().assert()
            .isTrue()
    }

    @Test
    fun timeConditionsReadTheRecordedTimestampAndTheFactoryIsRestored(@TempDir dir: Path) {
        val original = ConditionMatcherFactoryProvider.getRequired(TimeConditionMatcherFactory.TYPE)
        // Office hours allow the 10:30 sample and deny the 20:30 one, whatever the time of this run.
        val decided = mutableMapOf<String, Boolean>()
        val recording = AuthorizationFactory { policyRepository, appRolePermissionRepository, blacklist, root ->
            val engine = AuthorizationFactory.ENGINE.create(
                policyRepository,
                appRolePermissionRepository,
                blacklist,
                root
            )
            object : DecisionAuthorization {
                override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> =
                    engine.decide(request, context).doOnNext {
                        if (request.path == "/open/news") {
                            decided[request.requestId] = it.result.authorized
                        }
                    }
            }
        }
        DecisionReplay(candidate = recording).run(export(dir))
        decided.assert().isEqualTo(mapOf("r4" to true, "r5" to false))
        ConditionMatcherFactoryProvider.getRequired(TimeConditionMatcherFactory.TYPE).assert().isSameAs(original)
    }

    @Test
    fun anInjectedDifferenceShowsInTheReportAndTheMismatches(@TempDir dir: Path) {
        val unblocked = AuthorizationFactory { policyRepository, appRolePermissionRepository, _, root ->
            AuthorizationFactory.ENGINE.create(
                policyRepository,
                appRolePermissionRepository,
                BlacklistChecker.NoOp,
                root
            )
        }
        val candidate = AuthorizationFactory { policyRepository, appRolePermissionRepository, blacklist, root ->
            val ignoringBlacklist = unblocked.create(policyRepository, appRolePermissionRepository, blacklist, root)
            engineExcept(
                mapOf(
                    // Lets the blacklisted sample through: a deliberate difference.
                    "r6" to answer { request, context -> ignoringBlacklist.decide(request, context) },
                    "r10" to answer { _, _ -> Mono.error(IllegalStateException("engine bug")) },
                ),
            ).create(policyRepository, appRolePermissionRepository, blacklist, root)
        }
        val report = DecisionReplay(candidate = candidate, gate = LENIENT).run(export(dir))

        report.mismatch.assert().isEqualTo(1L)
        report.candidateError.assert().isEqualTo(1L)
        report.same.assert().isEqualTo(14L)
        report.candidateErrors[IllegalStateException::class.java.name].assert().isEqualTo(1L)
        val mismatches = Files.readAllLines(dir.resolve(DecisionReplay.MISMATCHES_FILE))
            .map { CoSecJsonSerializer.readTree(it) }
        mismatches.map { it.get("line").asInt() }.assert().containsExactly(6, 10)
        mismatches.map { it.get("verdict").asString() }.assert().containsExactly("MISMATCH", "CANDIDATE_ERROR")
        val blacklisted = mismatches[0]
        blacklisted.get("event").get("eventId").asString().assert().isEqualTo("e6")
        blacklisted.get("baseline").get("decidedBy").get("layer").asString().assert().isEqualTo("BLACKLIST")
        blacklisted.get("candidate").get("status").asString().assert().isEqualTo("ALLOWED")
        mismatches[1].get("candidate").get("error").asString().assert()
            .isEqualTo(IllegalStateException::class.java.name)
        mismatches[1].get("candidateCause").asString().assert().isEqualTo("engine bug")
    }

    @Test
    fun failuresOfOneClassForDifferentCausesAreListed(@TempDir dir: Path) {
        // The missing-policy sample, failing for another id.
        val candidate =
            engineExcept(mapOf("r8" to answer { _, _ -> Mono.error(MissingPolicyException(setOf("other"))) }))
        val report = DecisionReplay(candidate = candidate, gate = LENIENT).run(export(dir))
        report.same.assert().isEqualTo(16L)
        report.failedAlikeForDifferentCauses.assert().isEqualTo(1L)
        // The tuple compares the exception class only, but a different cause is still a difference.
        report.differences.assert().isEqualTo(1L)
        exitCodeOf(report).assert().isEqualTo(REPLAY_DIFFERENCES)
        report.summary().assert().contains("failed_alike_for_different_causes=1").contains("DIFFERENCES: ")
        val listed = CoSecJsonSerializer.readTree(
            Files.readAllLines(dir.resolve(DecisionReplay.MISMATCHES_FILE)).single()
        )
        listed.get("verdict").asString().assert().isEqualTo("SAME")
        listed.get("flag").asString().assert().isEqualTo("FAILED_ALIKE_FOR_DIFFERENT_CAUSES")
        listed.get("baselineCause").asString().assert().isEqualTo("PRINCIPAL policies [deleted]")
        listed.get("candidateCause").asString().assert().isEqualTo("PRINCIPAL policies [other]")
    }

    @Test
    fun withoutReplayConfigNoPrincipalIsRoot(@TempDir dir: Path) {
        val report = DecisionReplay().run(export(dir, FILES - DecisionReplay.CONFIG_FILE))
        report.mismatch.assert().isEqualTo(0L)
        report.inputs.keys.assert().doesNotContain(DecisionReplay.CONFIG_FILE)
        report.byLayer.keys.assert().doesNotContain("ROOT")
        // The root sample is now denied by the admin deny, so it no longer agrees with the recording.
        report.recordedAgreement.assert().isEqualTo(13L)
    }

    @Test
    fun timestampsMayBeEpochSecondsAndBlankLinesAreSkipped(@TempDir dir: Path) {
        export(dir, listOf(DecisionReplay.POLICIES_FILE, DecisionReplay.APP_ROLE_PERMISSIONS_FILE))
        val line = Files.readAllLines(
            Path.of(javaClass.getResource("/cosec-replay/${DecisionReplay.REQUESTS_FILE}")!!.toURI()),
        ).first().replace("\"2026-10-01T10:00:00Z\"", "1759312800.5")
        Files.write(dir.resolve(DecisionReplay.REQUESTS_FILE), listOf("", line, "  "))
        val report = DecisionReplay().run(dir)
        report.samples.assert().isEqualTo(1L)
        report.from.assert().isEqualTo(Instant.parse("2025-10-01T10:00:00.5Z"))
    }

    @Test
    fun aFailedRunLeavesNoReportBehind(@TempDir dir: Path) {
        DecisionReplay().run(export(dir))
        Files.exists(dir.resolve(DecisionReplay.REPORT_FILE)).assert().isTrue()

        Files.write(dir.resolve(DecisionReplay.REQUESTS_FILE), listOf("""{ "timestamp": "2026-10-01T10:00:00Z" }"""))
        assertThrows(IllegalArgumentException::class.java) { DecisionReplay().run(dir) }.message.assert()
            .contains("line 1")
        Files.exists(dir.resolve(DecisionReplay.REPORT_FILE)).assert().isFalse()
        Files.exists(dir.resolve(DecisionReplay.MISMATCHES_FILE)).assert().isFalse()
        Files.list(
            dir
        ).use { files -> files.noneMatch { it.fileName.toString().endsWith(".partial") }.assert().isTrue() }
    }

    @Test
    fun malformedInputFails(@TempDir dir: Path) {
        export(dir, listOf(DecisionReplay.APP_ROLE_PERMISSIONS_FILE, DecisionReplay.REQUESTS_FILE))
        Files.writeString(dir.resolve(DecisionReplay.POLICIES_FILE), "{}")
        assertThrows(IllegalArgumentException::class.java) { DecisionReplay().run(dir) }

        val policy = """{ "id": "p", "name": "p", "type": "global", "tenantId": "t", "statements": [] }"""
        Files.writeString(dir.resolve(DecisionReplay.POLICIES_FILE), "[$policy, $policy]")
        assertThrows(IllegalArgumentException::class.java) { DecisionReplay().run(dir) }.message.assert()
            .contains("[p] more than once")
    }

    @Test
    fun malformedGrantsFail(@TempDir dir: Path) {
        export(dir, listOf(DecisionReplay.POLICIES_FILE, DecisionReplay.REQUESTS_FILE))
        Files.writeString(
            dir.resolve(DecisionReplay.APP_ROLE_PERMISSIONS_FILE),
            """[{ "appPermission": { "id": "a", "groups": [] }, "grants": { "admin@a@b": ["*"] } }]""",
        )
        assertThrows(IllegalArgumentException::class.java) { DecisionReplay().run(dir) }.message.assert()
            .contains("admin@a@b")
    }

    @Test
    fun theCommandLineExitsWithWhatTheReplayFound(@TempDir dir: Path) {
        val directory = export(dir).toString()
        val lenient = arrayOf(
            directory,
            "--min-samples=10",
            "--min-days=1",
            "--min-distinct-days=1",
            "--max-both-failed-rate=0.2",
            "--min-agreement-rate=0.5",
        )

        // Met only with lenient thresholds: not evidence.
        val (lenientCode, lenientOut) = runMain(*lenient)
        lenientCode.assert().isEqualTo(REPLAY_GATE_NOT_MET)
        lenientOut.assert().contains("Gate met (lenient thresholds — not gate evidence)").contains("Report: ")

        val (notMet, notMetOut) = runMain(directory)
        notMet.assert().isEqualTo(REPLAY_GATE_NOT_MET)
        notMetOut.assert().contains("GATE NOT MET")

        val failing = engineExcept(mapOf("r10" to answer { _, _ -> Mono.error(IllegalStateException("engine bug")) }))
        val (candidateError, candidateErrorOut) = runMain(*lenient, candidate = failing)
        candidateError.assert().isEqualTo(REPLAY_DIFFERENCES)
        candidateErrorOut.assert().contains("candidate_error=1").contains("Differences: ")

        val denying = engineExcept(
            mapOf("r1" to answer { _, _ -> Mono.just(AuthorizationDecision(AuthorizeResult.IMPLICIT_DENY)) }),
        )
        // Differences win over an unmet gate.
        runMain(directory, candidate = denying).first.assert().isEqualTo(REPLAY_DIFFERENCES)

        runMain().first.assert().isEqualTo(REPLAY_USAGE)
        runMain("--min-samples=1").first.assert().isEqualTo(REPLAY_USAGE)
        listOf(
            "--min-samples=many",
            "--min-samples=0",
            "--min-days=NaN",
            "--max-both-failed-rate=2",
            "--min-agreement-rate=-1"
        )
            .forEach { runMain(directory, it).first.assert().describedAs(it).isEqualTo(REPLAY_USAGE) }
        val (unknown, unknownOut) = runMain(directory, "--verbose")
        unknown.assert().isEqualTo(REPLAY_USAGE)
        unknownOut.assert().contains("Usage: DecisionReplay")
    }

    @Test
    fun theCommandLineDeletesOldOutputsFirstAndReportsUnreplayableInput(@TempDir dir: Path) {
        export(dir)
        DecisionReplay().run(dir)
        Files.exists(dir.resolve(DecisionReplay.REPORT_FILE)).assert().isTrue()
        // Even arguments too wrong to replay leave no earlier report behind.
        runMain(dir.toString(), "--verbose").first.assert().isEqualTo(REPLAY_USAGE)
        Files.exists(dir.resolve(DecisionReplay.REPORT_FILE)).assert().isFalse()
        Files.exists(dir.resolve(DecisionReplay.MISMATCHES_FILE)).assert().isFalse()

        Files.write(dir.resolve(DecisionReplay.REQUESTS_FILE), listOf("not json"))
        val (invalid, invalidOut) = runMain(dir.toString())
        invalid.assert().isEqualTo(REPLAY_INVALID_INPUT)
        invalidOut.assert().contains("INVALID INPUT: requests.jsonl line 1 is not a replayable event.")
    }

    @Test
    fun customConditionTypesAreUnrecordedInputs() {
        val policy = CoSecJsonSerializer.readTree(
            """
            {
              "id": "geo",
              "condition": { "bool": { "and": [ { "all": {} }, { "geoFence": { "region": "eu" } } ] } },
              "statements": [ { "action": "*", "condition": { "eq": { "part": "request.path", "value": "/" } } } ]
            }
            """.trimIndent(),
        )
        UnrecordedInputs.of(
            listOf(policy),
            emptyList()
        ).assert().isEqualTo(mapOf("policy:geo" to setOf("custom:geoFence")))
    }

    @Test
    fun unrecordedInputsOfParts() {
        UnrecordedInputs.inputOf("request.header.X-Tenant").assert().isEqualTo("request.headers")
        UnrecordedInputs.inputOf("request.origin.host").assert().isEqualTo("request.headers")
        UnrecordedInputs.inputOf("request.referer").assert().isEqualTo("request.headers")
        UnrecordedInputs.inputOf("request.attributes.cosec.audit.routeId").assert().isNull()
        UnrecordedInputs.inputOf("request.attributes.x").assert().isEqualTo("request.attributes")
        UnrecordedInputs.inputOf("resource.owner").assert().isEqualTo("request.attributes")
        UnrecordedInputs.inputOf("context.principal.attributes.level").assert().isEqualTo("principal.attributes")
        UnrecordedInputs.inputOf("request.path").assert().isNull()
        UnrecordedInputs.inputOf("context.tenantId").assert().isNull()
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
    }
}
