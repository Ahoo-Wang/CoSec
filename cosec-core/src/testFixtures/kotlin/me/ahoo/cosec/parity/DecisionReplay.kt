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

import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.tenant.TenantCapable
import me.ahoo.cosec.authorization.RoleScope
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.policy.condition.ConditionMatcherFactoryProvider
import me.ahoo.cosec.policy.condition.TimeConditionMatcherFactory
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.DateTimeException
import java.util.HexFormat

/**
 * Offline replay of exported traffic (RFC 0002 §8.2.1): every recorded request is decided again by [baseline] and
 * [candidate] over the same exported policies, and the answers are compared with [DecisionParity].
 *
 * Run it on the maintainer's machine: `./gradlew :cosec-core:engineReplay -PreplayDir=<dir>` (see [replayMain] for the
 * options and exit codes). It reads only the directory and writes only the two output files into it. It never uploads
 * anything. The exported data may hold personal data and real policies: keep it out of the repository, PRs and CI.
 *
 * ## Input (all UTF-8 JSON, in `<dir>`)
 *
 * - `policies.json`: an array of policies, each as in `schema/cosec-policy.schema.json`, with unique ids. Include the
 *   global policies and every policy the sampled principals reference. A referenced policy left out replays as missing
 *   (`MissingPolicyException`) on both sides.
 * - `app-role-permissions.json`: an array, one entry per app, holding what `AppRolePermissionRepository` reads:
 *   ```json
 *   [{ "appPermission": { "id": "billing", "groups": [ ... ] },
 *      "grants": { "admin": ["*"], "auditor@s1": ["invoice.read"] } }]
 *   ```
 *   `appPermission` follows `schema/cosec-app-permission.schema.json`. `grants` maps a role, with an optional `@space`
 *   suffix, to the permission ids granted to it in that app and space, in grant order. Ids the app does not define are
 *   kept: they replay the fail-closed `MissingPermissionException`.
 * - `requests.jsonl`: one audit event per line, as the audit sinks write it (`AuditEvent` serialized by
 *   `CoSecJsonSerializer`). Used fields:
 *   - `timestamp` (ISO-8601, or epoch seconds): the clock of `time` conditions;
 *   - `tenantId`;
 *   - `principal.id`, `principal.roles` (with `@space` suffixes, as recorded) and `principal.policies`;
 *     `principal.authenticated` is only checked against the id;
 *   - `request.id`, `request.appId`, `request.spaceId`, `request.remoteIp`, `request.method`, `request.path`,
 *     `request.routeId`, `request.device.id` and `request.device.userAgent`;
 *   - `authorization.decision`, `authorization.reasonCode` and `authorization.source`: `source.type == BLACKLIST`
 *     replays the blacklist's verdict, and all three give the agreement with the recorded decision.
 *
 *   Optional fields the audit event does not carry, for whatever the export can recover:
 *   `principal.attributes` (object), `request.headers` (object of strings) and `request.attributes` (object of
 *   strings). Inputs left out are empty on both sides.
 * - `replay.json` (optional): the deployment's root configuration, `{ "rootEnabled": false, "rootId": "cosec" }`.
 *   When enabled, a principal is root if its id is `rootId` and its tenant is the platform tenant, as
 *   `RootPrincipalMatcher.DEFAULT`. Absent: no principal is root, as the starter's default.
 *
 * ## Output (in `<dir>`)
 *
 * Both files are deleted when a replay starts and written whole when it ends, so a failed run leaves neither behind.
 *
 * - `replay-report.json` ([ReplayReport]): counts, the time span, breakdowns, the agreement with the recorded
 *   decisions (for reference: policies may have changed during the sampled window), the policies and app permissions
 *   that read inputs audit events do not record, the SHA-256 of each input file, and whether the [gate] is met. Only
 *   the counts and hashes go into a PR as gate evidence.
 * - `replay-mismatches.jsonl`: one line per sample not [ParityVerdict.SAME], and per sample both sides failed alike
 *   for different causes, with the recorded event, both answers and both failure causes. Never share it as is: reduce
 *   a difference to a synthetic case first.
 *
 * Custom matchers the policies use must be on the classpath, and requests are rebuilt as plain data, so an
 * implementation that reads anything else of the request or context than the inputs above is not replayed faithfully.
 */
class DecisionReplay(
    private val baseline: AuthorizationFactory = AuthorizationFactory.LEGACY,
    private val candidate: AuthorizationFactory = AuthorizationFactory.ENGINE,
    private val gate: ReplayGate = ReplayGate()
) {
    companion object {
        const val POLICIES_FILE = "policies.json"
        const val APP_ROLE_PERMISSIONS_FILE = "app-role-permissions.json"
        const val REQUESTS_FILE = "requests.jsonl"
        const val CONFIG_FILE = "replay.json"
        const val REPORT_FILE = "replay-report.json"
        const val MISMATCHES_FILE = "replay-mismatches.jsonl"
        private const val PARTIAL_SUFFIX = ".partial"
        private const val FAILED_ALIKE_FOR_DIFFERENT_CAUSES = "FAILED_ALIKE_FOR_DIFFERENT_CAUSES"
    }

    /**
     * Replays `directory` and writes the report and the mismatches into it.
     *
     * While it runs, `time` conditions are created on the replay's clock: it swaps the global
     * [ConditionMatcherFactoryProvider] entry and restores it afterwards, so do not load policies concurrently.
     */
    fun run(directory: Path): ReplayReport {
        val reportFile = directory.resolve(REPORT_FILE)
        val mismatchesFile = directory.resolve(MISMATCHES_FILE)
        // A report left by an earlier run must never pass for this one's.
        Files.deleteIfExists(reportFile)
        Files.deleteIfExists(mismatchesFile)
        val clock = ReplayClock()
        // Time conditions read the event's timestamp, so policies must be read after the factory is swapped in.
        val previous = ConditionMatcherFactoryProvider.get(TimeConditionMatcherFactory.TYPE)
        ConditionMatcherFactoryProvider.register(ReplayTimeConditionMatcherFactory(clock))
        try {
            return replay(directory, clock)
        } finally {
            ConditionMatcherFactoryProvider.register(previous ?: TimeConditionMatcherFactory())
            Files.deleteIfExists(partial(reportFile))
            Files.deleteIfExists(partial(mismatchesFile))
        }
    }

    private fun replay(directory: Path, clock: ReplayClock): ReplayReport {
        val inputs = linkedMapOf<String, String>()
        val policyNodes = readArray(directory.resolve(POLICIES_FILE), inputs)
        val appNodes = readArray(directory.resolve(APP_ROLE_PERMISSIONS_FILE), inputs)
        val rootPrincipalMatcher = rootPrincipalMatcher(directory.resolve(CONFIG_FILE), inputs)
        val policies = policyNodes.map { CoSecJsonSerializer.treeToValue(it, Policy::class.java) }
        val apps = appNodes.map(::appEntry)

        val blacklist = ReplayBlacklist()
        val policyRepository = ReplayPolicyRepository(policies)
        val appRolePermissionRepository = ReplayAppRolePermissionRepository(apps.associateBy { it.appPermission.id })
        val parity = DecisionParity(
            baseline.create(policyRepository, appRolePermissionRepository, blacklist, rootPrincipalMatcher),
            candidate.create(policyRepository, appRolePermissionRepository, blacklist, rootPrincipalMatcher),
        )

        val counts = ReplayCounts()
        val mismatchesFile = directory.resolve(MISMATCHES_FILE)
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newBufferedWriter(partial(mismatchesFile)).use { mismatches ->
            // The hash covers exactly the bytes replayed.
            DigestInputStream(Files.newInputStream(directory.resolve(REQUESTS_FILE)), digest).bufferedReader()
                .useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (line.isBlank()) {
                            return@forEachIndexed
                        }
                        val event = parseEvent(index + 1, line)
                        clock.instant = event.timestamp
                        blacklist.blocked = event.blocked
                        val result = parity.compare(event.request, event.principal)
                        counts.add(event, result)
                        if (result.verdict != ParityVerdict.SAME || result.failedAlikeForDifferentCauses) {
                            mismatches.writeMismatch(index + 1, line, result)
                        }
                    }
                }
        }
        inputs[REQUESTS_FILE] = HexFormat.of().formatHex(digest.digest())
        val report = counts.toReport(UnrecordedInputs.of(policyNodes, appNodes), inputs, gate)
        val reportFile = directory.resolve(REPORT_FILE)
        CoSecJsonSerializer.writerWithDefaultPrettyPrinter().writeValue(partial(reportFile).toFile(), report)
        Files.move(partial(mismatchesFile), mismatchesFile, StandardCopyOption.REPLACE_EXISTING)
        Files.move(partial(reportFile), reportFile, StandardCopyOption.REPLACE_EXISTING)
        return report
    }

    private fun partial(file: Path): Path = file.resolveSibling(file.fileName.toString() + PARTIAL_SUFFIX)

    @Suppress("ThrowsCount")
    private fun parseEvent(line: Int, text: String): ReplayEvent = try {
        ReplayEvent.parse(CoSecJsonSerializer.readTree(text))
    } catch (cause: JacksonException) {
        throw notReplayable(line, cause)
    } catch (cause: IllegalArgumentException) {
        throw notReplayable(line, cause)
    } catch (cause: DateTimeException) {
        throw notReplayable(line, cause)
    }

    private fun notReplayable(line: Int, cause: Throwable) =
        IllegalArgumentException("$REQUESTS_FILE line $line is not a replayable event.", cause)

    /** Reads [file] once, hashing the very bytes it parses. */
    private fun read(file: Path, inputs: MutableMap<String, String>): ByteArray {
        val bytes = Files.readAllBytes(file)
        inputs[file.fileName.toString()] = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        return bytes
    }

    private fun readArray(file: Path, inputs: MutableMap<String, String>): List<JsonNode> {
        val node = CoSecJsonSerializer.readTree(read(file, inputs))
        require(node.isArray) { "$file must hold a JSON array." }
        return node.values().toList()
    }

    private fun appEntry(node: JsonNode): ReplayApp {
        val appPermission = CoSecJsonSerializer.treeToValue(
            requireNotNull(node.get("appPermission")) { "An $APP_ROLE_PERMISSIONS_FILE entry has no appPermission." },
            AppPermission::class.java,
        )
        val grants = node.get("grants")?.properties()?.associate { (role, permissionIds) ->
            val spacedRole = requireNotNull(RoleScope.parse(role)) {
                "App [${appPermission.id}] grants malformed role [$role]: write `role` or `role@space`."
            }
            spacedRole to permissionIds.values().mapTo(LinkedHashSet()) { it.asString() }
        }.orEmpty()
        return ReplayApp(appPermission, grants)
    }

    private fun rootPrincipalMatcher(configFile: Path, inputs: MutableMap<String, String>): RootPrincipalMatcher {
        if (!Files.exists(configFile)) {
            return RootPrincipalMatcher.NONE
        }
        val config = CoSecJsonSerializer.readValue(read(configFile, inputs), ReplayConfig::class.java)
        if (!config.rootEnabled) {
            return RootPrincipalMatcher.NONE
        }
        return RootPrincipalMatcher {
            it.id == config.rootId && it is TenantCapable && it.tenant.isPlatformTenant
        }
    }

    private fun BufferedWriter.writeMismatch(line: Int, event: String, result: ParityResult) {
        val node = CoSecJsonSerializer.createObjectNode()
        node.put("line", line)
        node.put("verdict", result.verdict.name)
        if (result.failedAlikeForDifferentCauses) {
            node.put("flag", FAILED_ALIKE_FOR_DIFFERENT_CAUSES)
        }
        node.set("event", CoSecJsonSerializer.readTree(event))
        node.set("baseline", CoSecJsonSerializer.valueToTree<JsonNode>(result.baseline.tuple))
        node.put("baselineCause", result.baseline.failureCause)
        node.set("candidate", CoSecJsonSerializer.valueToTree<JsonNode>(result.candidate.tuple))
        node.put("candidateCause", result.candidate.failureCause)
        write(CoSecJsonSerializer.writeValueAsString(node))
        newLine()
    }
}

/**
 * The root configuration of the replayed deployment, read from `replay.json`.
 */
data class ReplayConfig(val rootEnabled: Boolean = false, val rootId: String = CoSecPrincipal.ROOT_ID)
