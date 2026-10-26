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
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.condition.BOOL_CONDITION_MATCHER_AND_KEY
import me.ahoo.cosec.policy.condition.BOOL_CONDITION_MATCHER_OR_KEY
import me.ahoo.cosec.policy.condition.BoolConditionMatcherFactory
import me.ahoo.cosec.policy.condition.ConditionMatcherFactory
import me.ahoo.cosec.policy.condition.TimeConditionMatcher
import me.ahoo.cosec.policy.condition.TimeConditionMatcherFactory
import me.ahoo.cosec.policy.condition.part.CONDITION_MATCHER_PART_KEY
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.tenant.SimpleTenant
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ServiceLoader

/**
 * One line of `requests.jsonl`, rebuilt as plain data.
 */
internal class ReplayEvent(
    val eventId: String?,
    val timestamp: Instant,
    val principal: CoSecPrincipal,
    val request: Request,
    val blocked: Boolean,
    val recorded: RecordedDecision,
    val supplemented: List<String>,
    /** False when the recorded `principal.authenticated` disagrees with the one derived from `principal.id`. */
    val authenticatedConsistent: Boolean
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
                eventId = node.text("eventId"),
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
                authenticatedConsistent = principalNode.get("authenticated")?.takeUnless { it.isNull }
                    ?.let { it.asBoolean() == principal.authenticated } ?: true,
            )
        }

        private fun requestOf(node: JsonNode, headers: Map<String, String>, attributes: Map<String, String>): Request {
            val recordedHeaders = mapOf(
                AppIdCapable.APP_ID_KEY to node.text("appId"),
                SpaceIdCapable.SPACE_ID_KEY to node.text("spaceId"),
                DeviceIdCapable.DEVICE_ID_KEY to node.get("device")?.text("id"),
                RequestIdCapable.REQUEST_ID_KEY to node.text("id"),
                USER_AGENT_HEADER to node.get("device")?.text("userAgent"),
            )
            // HTTP header names are case-insensitive. What the audit event recorded (app, space, device, request id) is
            // what was decided on, so it wins over an exported header naming the same field, even when it recorded none
            // (`null` is the default app or space).
            val allHeaders = sortedMapOf<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
                putAll(headers)
                recordedHeaders.forEach { (key, value) ->
                    if (value.isNullOrEmpty()) remove(key) else put(key, value)
                }
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
 * from the configuration as written: a `part` naming such an input, a SpEL or OGNL condition, a `#{...}` template, or a
 * condition type `cosec-core` does not define (`custom:<type>`: what a custom matcher reads is unknown).
 */
internal object UnrecordedInputs {
    private const val SCRIPT = "script"
    private const val CONDITION_KEY = "condition"
    private const val BUILT_IN_PACKAGE = "me.ahoo.cosec.policy.condition"
    private val SCRIPT_TYPES = setOf("spel", "ognl")
    private val BOOL_KEYS = listOf(BOOL_CONDITION_MATCHER_AND_KEY, BOOL_CONDITION_MATCHER_OR_KEY)

    /** The condition types `cosec-core` registers itself; any other is a custom matcher. */
    private val builtInConditionTypes: Set<String> by lazy {
        ServiceLoader.load(ConditionMatcherFactory::class.java)
            .filter { it.javaClass.name.startsWith(BUILT_IN_PACKAGE) }
            .mapTo(HashSet()) { it.type }
    }

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
                if (name == CONDITION_KEY && value.isObject) {
                    customTypes(value, inputs)
                }
                collect(value, inputs)
            }

            node.isArray -> node.values().forEach { collect(it, inputs) }
            node.isString && "#{" in node.asString() -> inputs += SCRIPT
        }
    }

    /** A condition is `{ "<type>": <configuration> }`; `bool` nests conditions under `and` and `or`. */
    private fun customTypes(condition: JsonNode, inputs: MutableSet<String>) {
        val (type, configuration) = condition.properties().firstOrNull() ?: return
        if (type !in builtInConditionTypes) {
            inputs += "custom:$type"
        }
        if (type == BoolConditionMatcherFactory.TYPE) {
            BOOL_KEYS.forEach { key -> configuration.get(key)?.values()?.forEach { customTypes(it, inputs) } }
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

/** [appPermission] is null for an app exported as having no app permission: its role layer is empty, as in 7.0. */
internal class ReplayApp(
    val appId: String,
    val appPermission: AppPermission?,
    val grants: Map<SpacedRoleId, Set<String>>
)

/** The verdict recorded for the sample being replayed. Samples are replayed one at a time. */
internal class ReplayBlacklist : BlacklistChecker {
    @Volatile
    var blocked: Boolean = false

    override fun check(request: Request, context: SecurityContext): Mono<Boolean> = Mono.just(!blocked)
}

/** The recorded timestamp of the sample being replayed. */
internal class ReplayClock : Clock() {
    @Volatile
    var instant: Instant = Instant.EPOCH

    override fun instant(): Instant = instant
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = throw UnsupportedOperationException()
}

internal class ReplayTimeConditionMatcherFactory(private val clock: Clock) : ConditionMatcherFactory {
    override val type: String = TimeConditionMatcherFactory.TYPE
    override fun create(configuration: Configuration): ConditionMatcher = TimeConditionMatcher(configuration, clock)
}

internal class ReplayPolicyRepository(policies: List<Policy>) : PolicyRepository {
    private val global = policies.filter { it.type == PolicyType.GLOBAL }
    private val byId = policies.associateBy { it.id }

    init {
        val duplicates = policies.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(
            duplicates.isEmpty()
        ) { "${DecisionReplay.POLICIES_FILE} declares policy ids $duplicates more than once." }
    }

    override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(global)
    override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> =
        Mono.just(policyIds.mapNotNull { byId[it] })

    override fun setPolicy(policy: Policy): Mono<Void> = Mono.error(UnsupportedOperationException())
}

/** Returns only the grants made in the requested app and space, as the store keyed by app, role and space does. */
internal class ReplayAppRolePermissionRepository(
    private val apps: Map<String, ReplayApp>
) : AppRolePermissionRepository {
    override fun getAppRolePermission(appId: String, spaceId: String, roleIds: Set<RoleId>): Mono<AppRolePermission> {
        val app = apps[appId] ?: return Mono.empty()
        val appPermission = app.appPermission ?: return Mono.empty()
        val grants = roleIds.mapNotNull { roleId ->
            app.grants[SpacedRoleId(roleId, spaceId)]?.let { RolePermissionData(roleId, it) }
        }
        return Mono.just(AppRolePermissionData(appPermission, grants))
    }
}
