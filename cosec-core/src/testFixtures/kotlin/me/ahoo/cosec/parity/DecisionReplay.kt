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

import me.ahoo.cosec.api.audit.AuditDecision
import me.ahoo.cosec.api.audit.AuditReasonCode
import me.ahoo.cosec.api.audit.AuditRequest
import me.ahoo.cosec.api.audit.AuditSourceType
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.DeviceIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.context.request.RequestIdCapable
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.api.principal.SpacedRoleId
import me.ahoo.cosec.api.tenant.TenantCapable
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RoleScope
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.explain.DecisionRef
import me.ahoo.cosec.explain.ExplainLayer
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.condition.ConditionMatcherFactory
import me.ahoo.cosec.policy.condition.ConditionMatcherFactoryProvider
import me.ahoo.cosec.policy.condition.TimeConditionMatcher
import me.ahoo.cosec.policy.condition.TimeConditionMatcherFactory
import me.ahoo.cosec.policy.condition.part.CONDITION_MATCHER_PART_KEY
import me.ahoo.cosec.policy.condition.part.RegexTimeoutException
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.tenant.SimpleTenant
import reactor.core.publisher.Mono
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.io.BufferedWriter
import java.math.BigDecimal
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.HexFormat
import kotlin.system.exitProcess

/**
 * Offline replay of exported traffic (RFC 0002 §8.2.1): every recorded request is decided again by [baseline] and
 * [candidate] over the same exported policies, and the answers are compared with [DecisionParity].
 *
 * Run it on the maintainer's machine: `./gradlew :cosec-core:engineReplay -PreplayDir=<dir>`. It reads only the
 * directory and writes only the two output files into it. It never uploads anything. The exported data may hold
 * personal data and real policies: keep it out of the repository, PRs and CI.
 *
 * ## Input (all UTF-8 JSON, in `<dir>`)
 *
 * - `policies.json`: an array of policies, each as in `schema/cosec-policy.schema.json`. Include the global policies
 *   and every policy the sampled principals reference. A referenced policy left out replays as missing
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
 * - `replay-report.json` ([ReplayReport]): the sample count, the time span, the count of each [ParityVerdict], the
 *   samples where both sides failed alike, the agreement with the recorded decisions (for reference: policies may
 *   have changed during the sampled window), the policies and app permissions that read inputs audit events do not
 *   record, and the SHA-256 of each input file. Only the counts and hashes go into a PR as gate evidence.
 * - `replay-mismatches.jsonl`: one line per sample not [ParityVerdict.SAME], with the recorded event and both
 *   answers. Never share it as is: reduce a difference to a synthetic case first.
 *
 * Custom matchers the policies use must be on the classpath, and requests are rebuilt as plain data, so an
 * implementation that reads anything else of the request or context than the inputs above is not replayed faithfully.
 */
class DecisionReplay(
    private val baseline: AuthorizationFactory = AuthorizationFactory.LEGACY,
    private val candidate: AuthorizationFactory = AuthorizationFactory.ENGINE
) {
    companion object {
        const val POLICIES_FILE = "policies.json"
        const val APP_ROLE_PERMISSIONS_FILE = "app-role-permissions.json"
        const val REQUESTS_FILE = "requests.jsonl"
        const val CONFIG_FILE = "replay.json"
        const val REPORT_FILE = "replay-report.json"
        const val MISMATCHES_FILE = "replay-mismatches.jsonl"
    }

    /**
     * Replays `directory` and writes the report and the mismatches into it.
     *
     * While it runs, `time` conditions are created on the replay's clock: it swaps the global
     * [ConditionMatcherFactoryProvider] entry and restores it afterwards, so do not load policies concurrently.
     */
    fun run(directory: Path): ReplayReport {
        val clock = ReplayClock()
        // Time conditions read the event's timestamp, so policies must be read after the factory is swapped in.
        val previous = ConditionMatcherFactoryProvider.get(TimeConditionMatcherFactory.TYPE)
        ConditionMatcherFactoryProvider.register(ReplayTimeConditionMatcherFactory(clock))
        try {
            return replay(directory, clock)
        } finally {
            ConditionMatcherFactoryProvider.register(previous ?: TimeConditionMatcherFactory())
        }
    }

    private fun replay(directory: Path, clock: ReplayClock): ReplayReport {
        val policiesFile = directory.resolve(POLICIES_FILE)
        val appRolePermissionsFile = directory.resolve(APP_ROLE_PERMISSIONS_FILE)
        val requestsFile = directory.resolve(REQUESTS_FILE)
        val configFile = directory.resolve(CONFIG_FILE)
        val policyNodes = readArray(policiesFile)
        val appNodes = readArray(appRolePermissionsFile)
        val policies = policyNodes.map { CoSecJsonSerializer.treeToValue(it, Policy::class.java) }
        val apps = appNodes.map(::appEntry)
        val rootPrincipalMatcher = rootPrincipalMatcher(configFile)

        val blacklist = ReplayBlacklist()
        val policyRepository = ReplayPolicyRepository(policies)
        val appRolePermissionRepository = ReplayAppRolePermissionRepository(apps.associateBy { it.appPermission.id })
        val parity = DecisionParity(
            baseline.create(policyRepository, appRolePermissionRepository, blacklist, rootPrincipalMatcher),
            candidate.create(policyRepository, appRolePermissionRepository, blacklist, rootPrincipalMatcher),
        )

        val counts = ReplayCounts()
        Files.newBufferedWriter(directory.resolve(MISMATCHES_FILE)).use { mismatches ->
            Files.newBufferedReader(requestsFile).useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (line.isBlank()) {
                        return@forEachIndexed
                    }
                    val event = parseEvent(index + 1, line)
                    clock.instant = event.timestamp
                    blacklist.blocked = event.blocked
                    val result = parity.compare(event.request, event.principal)
                    counts.add(event, result)
                    if (result.verdict != ParityVerdict.SAME) {
                        mismatches.writeMismatch(index + 1, line, result)
                    }
                }
            }
        }
        val inputs = listOf(policiesFile, appRolePermissionsFile, requestsFile, configFile)
            .filter { Files.exists(it) }
            .associate { it.fileName.toString() to sha256(it) }
        val report = counts.toReport(UnrecordedInputs.of(policyNodes, appNodes), inputs)
        CoSecJsonSerializer.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(REPORT_FILE).toFile(), report)
        return report
    }

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

    private fun readArray(file: Path): List<JsonNode> {
        val node = CoSecJsonSerializer.readTree(Files.readAllBytes(file))
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

    private fun rootPrincipalMatcher(configFile: Path): RootPrincipalMatcher {
        if (!Files.exists(configFile)) {
            return RootPrincipalMatcher.NONE
        }
        val config = CoSecJsonSerializer.readValue(Files.readAllBytes(configFile), ReplayConfig::class.java)
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
        node.set("event", CoSecJsonSerializer.readTree(event))
        node.set("baseline", CoSecJsonSerializer.valueToTree<JsonNode>(result.baseline.tuple))
        node.set("candidate", CoSecJsonSerializer.valueToTree<JsonNode>(result.candidate.tuple))
        write(CoSecJsonSerializer.writeValueAsString(node))
        newLine()
    }

    private fun sha256(file: Path): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
}

/**
 * The root configuration of the replayed deployment, read from `replay.json`.
 */
data class ReplayConfig(val rootEnabled: Boolean = false, val rootId: String = CoSecPrincipal.ROOT_ID)

/**
 * The outcome of a replay, written to `replay-report.json`.
 *
 * @param from the earliest recorded timestamp, null without samples
 * @param to the latest recorded timestamp, null without samples
 * @param bothFailed samples where both sides failed with the same exception class (counted in [same] too)
 * @param recordedAgreement samples whose baseline decision agrees with the recorded one, out of [recordedCompared];
 * token failures are not compared (the replay has no token), a rate-limited request counts as allowed
 * @param unrecordedInputs policies (`policy:<id>`) and app permissions (`app:<id>`) mapped to the unrecorded inputs
 * their conditions read: `principal.attributes`, `request.headers`, `request.attributes`, or `script` for SpEL/OGNL
 * conditions and `#{...}` templates
 * @param supplemented how many samples carried each optional input
 * @param inputs the SHA-256 of each input file
 */
data class ReplayReport(
    val samples: Long,
    val from: Instant?,
    val to: Instant?,
    val spanDays: Double,
    val same: Long,
    val mismatch: Long,
    val candidateError: Long,
    val bothFailed: Long,
    val recordedAgreement: Long,
    val recordedCompared: Long,
    val recordedAgreementRate: Double,
    val unrecordedInputs: Map<String, Set<String>>,
    val supplemented: Map<String, Long>,
    val inputs: Map<String, String>
) {
    fun summary(): String = buildString {
        appendLine("Replayed $samples samples from $from to $to (${"%.2f".format(spanDays)} days).")
        appendLine("same=$same mismatch=$mismatch candidate_error=$candidateError (both failed alike: $bothFailed)")
        appendLine(
            "Agreement with recorded decisions: $recordedAgreement / $recordedCompared " +
                "(${"%.4f".format(recordedAgreementRate)}), for reference only.",
        )
        if (unrecordedInputs.isNotEmpty()) {
            appendLine("Read unrecorded inputs: $unrecordedInputs")
        }
        inputs.forEach { (file, hash) -> appendLine("sha256 $file $hash") }
    }
}

private class ReplayCounts {
    var samples = 0L
    var from: Instant? = null
    var to: Instant? = null
    val verdicts = LongArray(ParityVerdict.entries.size)
    var bothFailed = 0L
    var agreed = 0L
    var compared = 0L
    val supplemented = sortedMapOf<String, Long>()

    fun add(event: ReplayEvent, result: ParityResult) {
        samples++
        from = from?.let { minOf(it, event.timestamp) } ?: event.timestamp
        to = to?.let { maxOf(it, event.timestamp) } ?: event.timestamp
        verdicts[result.verdict.ordinal]++
        if (result.bothFailed) {
            bothFailed++
        }
        event.supplemented.forEach { supplemented.merge(it, 1L, Long::plus) }
        event.recorded.agrees(result.baseline)?.let { agrees ->
            compared++
            if (agrees) {
                agreed++
            }
        }
    }

    fun toReport(unrecordedInputs: Map<String, Set<String>>, inputs: Map<String, String>): ReplayReport {
        val span = if (from != null && to != null) Duration.between(from, to) else Duration.ZERO
        return ReplayReport(
            samples = samples,
            from = from,
            to = to,
            spanDays = span.toMillis() / Duration.ofDays(1).toMillis().toDouble(),
            same = verdicts[ParityVerdict.SAME.ordinal],
            mismatch = verdicts[ParityVerdict.MISMATCH.ordinal],
            candidateError = verdicts[ParityVerdict.CANDIDATE_ERROR.ordinal],
            bothFailed = bothFailed,
            recordedAgreement = agreed,
            recordedCompared = compared,
            recordedAgreementRate = if (compared == 0L) 0.0 else agreed / compared.toDouble(),
            unrecordedInputs = unrecordedInputs,
            supplemented = supplemented,
            inputs = inputs,
        )
    }
}

/**
 * What the audit event recorded as the decision.
 */
private class RecordedDecision(
    val decision: AuditDecision,
    val reasonCode: AuditReasonCode,
    val source: AuditSourceType,
    val policyId: String?,
    val statementIndex: Int?,
    val roleId: String?,
    val permissionId: String?
) {
    /** Null when the replay cannot reproduce the recorded decision: it has no token to reject. */
    fun agrees(side: ParitySide): Boolean? {
        if (reasonCode == AuditReasonCode.TOKEN_EXPIRED || reasonCode == AuditReasonCode.TOKEN_INVALID) {
            return null
        }
        // Rate limiting only ever applies to allowed requests and is not replayed.
        val recorded = if (decision == AuditDecision.TOO_MANY_REQUESTS) AuditDecision.ALLOW else decision
        return recorded == replayedDecision(side) && sameSource(side.tuple.decidedBy)
    }

    private fun replayedDecision(side: ParitySide): AuditDecision = when {
        side.failure != null && side.failure !is RegexTimeoutException -> AuditDecision.ERROR
        side.tuple.status == SecurityStatus.ALLOWED -> AuditDecision.ALLOW
        side.tuple.reason == AuthorizeResult.IMPLICIT_DENY.reason -> AuditDecision.IMPLICIT_DENY
        else -> AuditDecision.EXPLICIT_DENY
    }

    private fun sameSource(decidedBy: DecisionRef?): Boolean {
        return when (source) {
            AuditSourceType.GLOBAL_POLICY, AuditSourceType.PRINCIPAL_POLICY ->
                decidedBy?.policyId == policyId && decidedBy?.statementIndex == statementIndex

            AuditSourceType.ROLE_PERMISSION -> decidedBy?.roleId == roleId && decidedBy?.permissionId == permissionId
            AuditSourceType.ROOT -> decidedBy?.layer == ExplainLayer.ROOT
            AuditSourceType.BLACKLIST -> decidedBy?.layer == ExplainLayer.BLACKLIST
            AuditSourceType.NONE, AuditSourceType.UNKNOWN -> true
        }
    }
}

/**
 * One line of `requests.jsonl`, rebuilt as plain data.
 */
private class ReplayEvent(
    val timestamp: Instant,
    val principal: CoSecPrincipal,
    val request: Request,
    val blocked: Boolean,
    val recorded: RecordedDecision,
    val supplemented: List<String>
) {
    companion object {
        private const val USER_AGENT_HEADER = "User-Agent"

        fun parse(node: JsonNode): ReplayEvent {
            val principalNode = node.required("principal")
            val requestNode = node.required("request")
            val authorization = node.required("authorization")
            val source = authorization.required("source")
            val principalAttributes = principalNode.get("attributes")?.let {
                CoSecJsonSerializer.treeToValue(it, Map::class.java).entries.associate { (key, value) ->
                    key.toString() to requireNotNull(value) { "principal.attributes.$key is null." }
                }
            }
            val headers = requestNode.get("headers")?.strings()
            val attributes = requestNode.get("attributes")?.strings()
            val principal = SimpleTenantPrincipal(
                SimplePrincipal(
                    id = principalNode.required("id").asString(),
                    policies = principalNode.get(
                        "policies"
                    )?.values()?.mapTo(LinkedHashSet()) { it.asString() }.orEmpty(),
                    roles = principalNode.get("roles")?.values()?.mapTo(LinkedHashSet()) { it.asString() }.orEmpty(),
                    attributes = principalAttributes.orEmpty(),
                ),
                SimpleTenant(node.required("tenantId").asString()),
            )
            return ReplayEvent(
                timestamp = instantOf(node.required("timestamp")),
                principal = principal,
                request = requestOf(requestNode, headers.orEmpty(), attributes.orEmpty()),
                blocked = source.required("type").asString() == AuditSourceType.BLACKLIST.name,
                recorded = recordedOf(authorization, source),
                supplemented = listOfNotNull(
                    principalAttributes?.let { "principal.attributes" },
                    headers?.let { "request.headers" },
                    attributes?.let { "request.attributes" },
                ),
            )
        }

        private fun requestOf(node: JsonNode, headers: Map<String, String>, attributes: Map<String, String>): Request {
            val recordedHeaders = mapOf(
                AppIdCapable.APP_ID_KEY to node.text("appId"),
                SpaceIdCapable.SPACE_ID_KEY to node.text("spaceId"),
                DeviceIdCapable.DEVICE_ID_KEY to node.get("device")?.text("id"),
                RequestIdCapable.REQUEST_ID_KEY to node.text("id"),
                USER_AGENT_HEADER to node.get("device")?.text("userAgent"),
            ).filterValues { !it.isNullOrEmpty() }.mapValues { it.value!! }
            // HTTP header names are case-insensitive; exported headers win over the recorded ones they repeat.
            val allHeaders = sortedMapOf<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
                putAll(recordedHeaders)
                putAll(headers)
            }
            val routeId = node.text("routeId")
            val allAttributes = if (routeId.isNullOrEmpty()) {
                attributes
            } else {
                mapOf(AuditRequest.ROUTE_ID_ATTRIBUTE_KEY to routeId) + attributes
            }
            return EvaluateRequest(
                path = node.required("path").asString(),
                method = node.required("method").asString(),
                remoteIp = node.text("remoteIp").orEmpty(),
                origin = URI.create(allHeaders["Origin"].orEmpty()),
                referer = URI.create(allHeaders["Referer"].orEmpty()),
                attributes = allAttributes,
                headers = allHeaders,
            )
        }

        private fun recordedOf(authorization: JsonNode, source: JsonNode): RecordedDecision {
            val policy = source.get("policy")?.takeUnless { it.isNull }
            val role = source.get("role")?.takeUnless { it.isNull }
            return RecordedDecision(
                decision = AuditDecision.valueOf(authorization.required("decision").asString()),
                reasonCode = AuditReasonCode.valueOf(authorization.required("reasonCode").asString()),
                source = AuditSourceType.valueOf(source.required("type").asString()),
                policyId = policy?.text("id"),
                statementIndex = policy?.get("statement")?.get("index")?.asInt(),
                roleId = role?.text("id"),
                permissionId = role?.get("permission")?.text("id"),
            )
        }

        private fun instantOf(node: JsonNode): Instant {
            if (node.isNumber) {
                val seconds = node.decimalValue()
                val nanos = seconds.remainder(BigDecimal.ONE).movePointRight(9).toLong()
                return Instant.ofEpochSecond(seconds.toLong(), nanos)
            }
            return Instant.parse(node.asString())
        }

        private fun JsonNode.required(field: String): JsonNode =
            get(field)?.takeUnless { it.isNull } ?: throw IllegalArgumentException("Missing field [$field].")

        private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asString()

        private fun JsonNode.strings(): Map<String, String> = properties().associate { it.key to it.value.asString() }
    }
}

/**
 * Finds the policies and app permissions whose conditions read inputs audit events do not record (RFC 0002 §8.2.1),
 * from the configuration as written: a `part` naming such an input, a SpEL or OGNL condition, or a `#{...}` template.
 */
internal object UnrecordedInputs {
    private const val SCRIPT = "script"
    private val SCRIPT_TYPES = setOf("spel", "ognl")

    fun of(policies: List<JsonNode>, apps: List<JsonNode>): Map<String, Set<String>> {
        val found = sortedMapOf<String, Set<String>>()
        policies.forEach { policy -> add(found, "policy:${policy.get("id")?.asString()}", policy) }
        apps.forEach { app ->
            val appPermission = app.get("appPermission") ?: return@forEach
            add(found, "app:${appPermission.get("id")?.asString()}", appPermission)
        }
        return found
    }

    private fun add(found: MutableMap<String, Set<String>>, key: String, node: JsonNode) {
        val inputs = sortedSetOf<String>()
        collect(node, inputs)
        if (inputs.isNotEmpty()) {
            found[key] = inputs
        }
    }

    private fun collect(node: JsonNode, inputs: MutableSet<String>) {
        when {
            node.isObject -> node.properties().forEach { (name, value) ->
                if (name in SCRIPT_TYPES && value.isObject) {
                    inputs += SCRIPT
                }
                if (name == CONDITION_MATCHER_PART_KEY && value.isString) {
                    inputOf(value.asString())?.let { inputs += it }
                }
                collect(value, inputs)
            }

            node.isArray -> node.values().forEach { collect(it, inputs) }
            node.isString && "#{" in node.asString() -> inputs += SCRIPT
        }
    }

    internal fun inputOf(part: String): String? = when {
        part.startsWith("request.header.") || part.startsWith("request.origin") || part.startsWith("request.referer") ->
            "request.headers"

        part == "request.attributes.${AuditRequest.ROUTE_ID_ATTRIBUTE_KEY}" -> null
        part.startsWith("request.attributes.") || part.startsWith("resource.") -> "request.attributes"
        part.startsWith("context.principal.attributes.") -> "principal.attributes"
        else -> null
    }
}

private class ReplayApp(val appPermission: AppPermission, val grants: Map<SpacedRoleId, Set<String>>)

/** The verdict recorded for the sample being replayed. Samples are replayed one at a time. */
private class ReplayBlacklist : BlacklistChecker {
    @Volatile
    var blocked: Boolean = false

    override fun check(request: Request, context: SecurityContext): Mono<Boolean> = Mono.just(!blocked)
}

/** The recorded timestamp of the sample being replayed. */
private class ReplayClock : Clock() {
    @Volatile
    var instant: Instant = Instant.EPOCH

    override fun instant(): Instant = instant
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = throw UnsupportedOperationException()
}

private class ReplayTimeConditionMatcherFactory(private val clock: Clock) : ConditionMatcherFactory {
    override val type: String = TimeConditionMatcherFactory.TYPE
    override fun create(configuration: Configuration): ConditionMatcher = TimeConditionMatcher(configuration, clock)
}

private class ReplayPolicyRepository(private val policies: List<Policy>) : PolicyRepository {
    private val global = policies.filter { it.type == PolicyType.GLOBAL }
    private val byId = policies.associateBy { it.id }

    override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(global)
    override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> =
        Mono.just(policyIds.mapNotNull { byId[it] })

    override fun setPolicy(policy: Policy): Mono<Void> = Mono.error(UnsupportedOperationException())
}

/** Returns only the grants made in the requested app and space, as the store keyed by app, role and space does. */
private class ReplayAppRolePermissionRepository(
    private val apps: Map<String, ReplayApp>
) : AppRolePermissionRepository {
    override fun getAppRolePermission(appId: String, spaceId: String, roleIds: Set<RoleId>): Mono<AppRolePermission> {
        val app = apps[appId] ?: return Mono.empty()
        val grants = roleIds.mapNotNull { roleId ->
            app.grants[SpacedRoleId(roleId, spaceId)]?.let { RolePermissionData(roleId, it) }
        }
        return Mono.just(AppRolePermissionData(app.appPermission, grants))
    }
}

/**
 * `./gradlew :cosec-core:engineReplay -PreplayDir=<dir>`: replays `<dir>` with [DecisionReplay] and exits with 1 when
 * any sample is not [ParityVerdict.SAME].
 */
fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: DecisionReplay <export directory>" }
    val directory = Path.of(args[0]).toAbsolutePath()
    val report = DecisionReplay().run(directory)
    println(report.summary())
    println("Report: ${directory.resolve(DecisionReplay.REPORT_FILE)}")
    if (report.mismatch + report.candidateError > 0) {
        println("Differences: ${directory.resolve(DecisionReplay.MISMATCHES_FILE)}")
        exitProcess(1)
    }
}
