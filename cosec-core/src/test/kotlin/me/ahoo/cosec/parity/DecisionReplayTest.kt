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

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.policy.condition.ConditionMatcherFactoryProvider
import me.ahoo.cosec.policy.condition.TimeConditionMatcherFactory
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reactor.core.publisher.Mono
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

    @Test
    fun replaysTheSyntheticExport(@TempDir dir: Path) {
        val report = DecisionReplay().run(export(dir))

        report.samples.assert().isEqualTo(14L)
        report.same.assert().isEqualTo(14L)
        report.mismatch.assert().isEqualTo(0L)
        report.candidateError.assert().isEqualTo(0L)
        // A missing principal policy and a grant of an undefined permission fail closed on both sides.
        report.bothFailed.assert().isEqualTo(2L)
        report.from.assert().isEqualTo(Instant.parse("2026-10-01T10:00:00Z"))
        report.to.assert().isEqualTo(Instant.parse("2026-10-03T10:00:00Z"))
        report.spanDays.assert().isEqualTo(2.0)
        // The token-expired sample is not compared; the reports policy changed after its sample was recorded.
        report.recordedCompared.assert().isEqualTo(13L)
        report.recordedAgreement.assert().isEqualTo(12L)
        report.unrecordedInputs.assert().isEqualTo(
            mapOf(
                "policy:orders" to setOf("principal.attributes", "request.headers"),
                "policy:reports" to setOf("script"),
            ),
        )
        report.supplemented.assert().isEqualTo(mapOf("principal.attributes" to 1L, "request.headers" to 3L))
        report.inputs.keys.assert().containsExactlyInAnyOrderElementsOf(FILES)
        report.inputs[DecisionReplay.REQUESTS_FILE].assert().isEqualTo(
            sha256(dir.resolve(DecisionReplay.REQUESTS_FILE))
        )

        val written = CoSecJsonSerializer.readTree(Files.readAllBytes(dir.resolve(DecisionReplay.REPORT_FILE)))
        written.get("samples").asLong().assert().isEqualTo(14L)
        written.get("inputs").get(DecisionReplay.POLICIES_FILE).asString().assert()
            .isEqualTo(sha256(dir.resolve(DecisionReplay.POLICIES_FILE)))
        Files.readAllLines(dir.resolve(DecisionReplay.MISMATCHES_FILE)).assert().isEmpty()
        report.summary().assert().contains("same=14 mismatch=0 candidate_error=0").contains("12 / 13")
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
        // Lets the blacklisted sample through: a deliberate difference.
        val ignoringBlacklist = AuthorizationFactory { policyRepository, appRolePermissionRepository, blacklist, root ->
            val engine = AuthorizationFactory.ENGINE.create(
                policyRepository,
                appRolePermissionRepository,
                blacklist,
                root
            )
            val unblocked = AuthorizationFactory.ENGINE.create(
                policyRepository,
                appRolePermissionRepository,
                BlacklistChecker.NoOp,
                root,
            )
            object : DecisionAuthorization {
                override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> =
                    if (request.requestId == "r6") {
                        unblocked.decide(
                            request,
                            context
                        )
                    } else {
                        engine.decide(request, context)
                    }
            }
        }
        val failing = AuthorizationFactory { policyRepository, appRolePermissionRepository, blacklist, root ->
            val engine = ignoringBlacklist.create(policyRepository, appRolePermissionRepository, blacklist, root)
            object : DecisionAuthorization {
                override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> =
                    if (request.requestId == "r10") {
                        Mono.error(IllegalStateException("engine bug"))
                    } else {
                        engine.decide(request, context)
                    }
            }
        }
        val report = DecisionReplay(candidate = failing).run(export(dir))

        report.mismatch.assert().isEqualTo(1L)
        report.candidateError.assert().isEqualTo(1L)
        report.same.assert().isEqualTo(12L)
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
    }

    @Test
    fun withoutReplayConfigNoPrincipalIsRoot(@TempDir dir: Path) {
        val report = DecisionReplay().run(
            export(dir, FILES - DecisionReplay.CONFIG_FILE),
        )
        report.mismatch.assert().isEqualTo(0L)
        report.inputs.keys.assert().doesNotContain(DecisionReplay.CONFIG_FILE)
        // The root sample is now denied by the admin deny, so it no longer agrees with the recording.
        report.recordedAgreement.assert().isEqualTo(11L)
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
    fun malformedInputFails(@TempDir dir: Path) {
        export(dir, listOf(DecisionReplay.POLICIES_FILE, DecisionReplay.APP_ROLE_PERMISSIONS_FILE))
        Files.write(dir.resolve(DecisionReplay.REQUESTS_FILE), listOf("""{ "timestamp": "2026-10-01T10:00:00Z" }"""))
        assertThrows(IllegalArgumentException::class.java) { DecisionReplay().run(dir) }.message.assert()
            .contains("line 1")

        Files.writeString(dir.resolve(DecisionReplay.POLICIES_FILE), "{}")
        assertThrows(IllegalArgumentException::class.java) { DecisionReplay().run(dir) }
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
    fun mainReplaysTheGivenDirectory(@TempDir dir: Path) {
        main(arrayOf(export(dir).toString()))
        Files.exists(dir.resolve(DecisionReplay.REPORT_FILE)).assert().isTrue()
        assertThrows(IllegalArgumentException::class.java) { main(emptyArray()) }
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
    }
}
