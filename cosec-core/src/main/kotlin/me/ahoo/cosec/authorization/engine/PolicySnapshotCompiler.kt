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

package me.ahoo.cosec.authorization.engine

import me.ahoo.cosec.api.context.request.SpaceId
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.PermissionId
import me.ahoo.cosec.api.permission.RolePermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyId
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.RoleScope
import me.ahoo.cosec.engine.LayerInput
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotCompiler
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotFormatException
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotUnavailableException
import tools.jackson.core.JacksonException
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.ObjectReader
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Collections

/**
 * Compiles snapshot documents (RFC 0003 §3.3, §3.4) into the adapter's rule groups: the JSON becomes the existing
 * [PolicyData] and [AppPermissionData] through the same deserializers 7.0's caches decode with, and those become the
 * groups and rules of RFC 0002 §3.3, once per revision.
 *
 * The body is the stored document. Its header fields, when present, must equal [SnapshotDocument]'s decoded header.
 *
 * What cannot be compiled:
 * - a malformed document (unparsable JSON, an unknown schema or field, a missing or repeated section, a repeated key
 *   anywhere in the body, a header that disagrees with the decoded one, a policy without an id, a duplicate id or
 *   role, a grant key that is not `role` or `role@space`, an app permission whose id is not the key's app): the
 *   compiler throws, and the whole document fails closed.
 *   Nothing is ever "last one wins": a repeated section could otherwise replace one that holds a deny;
 * - one policy, app permission or grant that the deserializers reject, including with a [LinkageError] such as a
 *   missing AOT hint causes: that entry is [Compiled.Broken], so only the layers reading it fail (§4.4 F4), as in 7.0,
 *   where only the requests reading that cache entry fail.
 *
 * Nothing is checked that 7.0's read path does not check: [me.ahoo.cosec.policy.DefaultPolicyEvaluator] runs when a
 * policy is written, not when it is read, so running it here would fail requests 7.0 decides.
 */
internal class PolicySnapshotCompiler(private val mapper: ObjectMapper = CoSecJsonSerializer) {
    /** Decodes grants exactly as 7.0's `RolePermissionCache` codec does, so the grant order is the same. */
    private val grantReader: ObjectReader = mapper.readerFor(object : TypeReference<Set<PermissionId>>() {})

    /** Rejects a repeated key at any depth, so the trees read from it hold no silently dropped value. */
    private val bodyReader: ObjectReader = mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    val policies: SnapshotCompiler<CompiledPolicies> = SnapshotCompiler { key, document ->
        require(key == SnapshotKey.Policies) { "Not a policies document: $key." }
        compilePolicies(document)
    }

    val app: SnapshotCompiler<CompiledApp> = SnapshotCompiler { key, document ->
        require(key is SnapshotKey.App) { "Not an app document: $key." }
        compileApp(key, document)
    }

    fun compilePolicies(document: SnapshotDocument): CompiledPolicies {
        val key = SnapshotKey.Policies
        var nodes: List<JsonNode>? = null
        parse(key, document) { parser, field ->
            when (field) {
                POLICIES -> nodes = parser.readArray(key, field)
                else -> return@parse false
            }
            true
        }
        val policies = nodes ?: malformed(key, "the [$POLICIES] section is missing")
        val byId = HashMap<PolicyId, Compiled<PolicyGroup>>(policies.size * 2)
        val globalGroups = ArrayList<PolicyGroup>()
        var globalError: Throwable? = null
        for (node in policies) {
            when (val compiled = compilePolicy(key, node, byId)) {
                is Compiled.Ok -> if (compiled.value.policy.type == PolicyType.GLOBAL) {
                    globalGroups.add(compiled.value)
                }
                // A broken policy may be global unless it says otherwise; the global layer then fails, as 7.0's
                // read of every global policy fails on one undecodable policy.
                is Compiled.Broken -> if (globalError == null && mayBeGlobal(node)) {
                    globalError = compiled.error
                }
            }
        }
        val globalLayer = when {
            globalError != null -> LayerInput.Unavailable(LayerKind.GLOBAL, globalError)
            globalGroups.isEmpty() -> NO_GLOBAL_POLICIES
            else -> LayerInput.Loaded(LayerKind.GLOBAL, Collections.unmodifiableList(globalGroups))
        }
        return CompiledPolicies(document.revision, globalLayer, Collections.unmodifiableMap(byId))
    }

    fun compileApp(key: SnapshotKey.App, document: SnapshotDocument): CompiledApp {
        var appPermissionNode: JsonNode? = null
        var grants: Map<SpaceId, Map<RoleId, Compiled<RolePermission>>> = emptyMap()
        parse(key, document) { parser, field ->
            when (field) {
                APP_PERMISSION -> appPermissionNode = parser.readObject(key, field)
                GRANTS -> grants = readGrants(key, parser)
                else -> return@parse false
            }
            true
        }
        val node = appPermissionNode ?: malformed(key, "the [$APP_PERMISSION] section is missing")
        // A document is bound to its key: app A's document stored under app B must not grant B what A grants.
        val appId = node.get(ID)?.takeIf { it.isString }?.asString()
        if (appId != key.appId) {
            malformed(key, "the app permission names app [$appId], not [${key.appId}]")
        }
        return CompiledApp(document.revision, key.appId, compileAppPermission(key, node), grants)
    }

    /** Compiles the policy [node] and adds it to [byId]. */
    private fun compilePolicy(
        key: SnapshotKey,
        node: JsonNode,
        byId: MutableMap<PolicyId, Compiled<PolicyGroup>>,
    ): Compiled<PolicyGroup> {
        val id = node.get(ID)?.takeIf { it.isString }?.asString()
            ?: malformed(key, "a policy has no string [$ID]")
        val compiled = entry(key, "policy [$id]") {
            val policy = mapper.treeToValue(node, Policy::class.java)
            if (policy is PolicyData) policy.engineGroup() else PolicyGroup(policy)
        }
        if (byId.putIfAbsent(id, compiled) != null) {
            malformed(key, "policy [$id] is listed twice")
        }
        return compiled
    }

    @Suppress("TooGenericExceptionCaught")
    private fun mayBeGlobal(node: JsonNode): Boolean {
        val type = node.get(POLICY_TYPE) ?: return true
        return try {
            (mapper.treeToValue(type, PolicyType::class.java) ?: PolicyType.GLOBAL) == PolicyType.GLOBAL
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            true
        }
    }

    private fun compileAppPermission(key: SnapshotKey, node: JsonNode): Compiled<AppPermissionRules> =
        entry(key, "the app permission") {
            val appPermission = mapper.treeToValue(node, AppPermission::class.java)
            if (appPermission is AppPermissionData) appPermission.engineRules() else AppPermissionRules(appPermission)
        }

    /**
     * Decodes one entry: [Compiled.Broken] when [decode] throws anything but a [VirtualMachineError], which no entry
     * causes and which must reach the compile pool's handler.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T : Any> entry(key: SnapshotKey, what: String, decode: () -> T): Compiled<T> = try {
        Compiled.Ok(decode())
    } catch (error: Throwable) {
        rethrowIfFatal(error)
        Compiled.Broken(SnapshotUnavailableException(key, "$what cannot be decoded", error))
    }

    private fun rethrowIfFatal(error: Throwable) {
        if (error is VirtualMachineError) {
            throw error
        }
    }

    private fun readGrants(key: SnapshotKey, parser: JsonParser): Map<SpaceId, Map<RoleId, Compiled<RolePermission>>> {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            malformed(key, "[$GRANTS] is not an object")
        }
        val grants = HashMap<SpaceId, HashMap<RoleId, Compiled<RolePermission>>>()
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            val name = parser.currentName()
            val role = RoleScope.parse(name) ?: malformed(key, "grant key [$name] is not a role or role@space")
            parser.nextToken()
            val node = parser.readValueAsTree<JsonNode>()
            val compiled: Compiled<RolePermission> = entry(key, "the grants of [$name]") {
                RolePermissionData(role.roleId, grantReader.readValue<Set<PermissionId>>(node))
            }
            if (grants.getOrPut(role.spaceId) { HashMap() }.putIfAbsent(role.roleId, compiled) != null) {
                malformed(key, "grant [$name] is listed twice")
            }
        }
        return Collections.unmodifiableMap(
            grants.mapValuesTo(HashMap(grants.size * 2)) { Collections.unmodifiableMap(it.value) }
        )
    }

    /**
     * Walks the document's top-level object, skipping the header fields and handing every other field to [section],
     * which reads its value and returns true, or returns false for a field it does not know.
     */
    private inline fun parse(
        key: SnapshotKey,
        document: SnapshotDocument,
        section: (JsonParser, String) -> Boolean,
    ) {
        if (document.schema != SnapshotDocument.CURRENT_SCHEMA) {
            malformed(key, "schema ${document.schema} is not supported")
        }
        try {
            bodyReader.createParser(document.bodyStream()).use { parser ->
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    malformed(key, "the body is not a JSON object")
                }
                val fields = HashSet<String>()
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    val field = parser.currentName()
                    // The strict parser rejects it too; this check does not depend on a parser feature.
                    if (!fields.add(field)) {
                        malformed(key, "[$field] is listed twice")
                    }
                    if (field in HEADER_FIELDS) {
                        parser.nextToken()
                        checkHeader(key, document, field, parser)
                        continue
                    }
                    if (!section(parser, field)) {
                        malformed(key, "unknown field [$field]")
                    }
                }
                if (parser.nextToken() != null) {
                    malformed(key, "trailing content after the document")
                }
            }
        } catch (error: JacksonException) {
            throw SnapshotFormatException("Snapshot document [$key] is not valid JSON.", error)
        }
    }

    /** The body's copy of a header field must be the header [document] was decoded with. */
    private fun checkHeader(key: SnapshotKey, document: SnapshotDocument, field: String, parser: JsonParser) {
        val token = parser.currentToken()
        val matches = when (field) {
            SCHEMA -> token == JsonToken.VALUE_NUMBER_INT && parser.longValue == document.schema.toLong()
            REVISION -> token == JsonToken.VALUE_NUMBER_INT && parser.longValue == document.revision
            else -> token == JsonToken.VALUE_STRING && parseInstant(parser.string) == document.publishedAt
        }
        if (!matches) {
            parser.skipChildren()
            malformed(key, "[$field] differs from the document's header")
        }
    }

    private fun parseInstant(text: String): Instant? = try {
        Instant.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun JsonParser.readArray(key: SnapshotKey, field: String): List<JsonNode> {
        if (nextToken() != JsonToken.START_ARRAY) {
            malformed(key, "[$field] is not an array")
        }
        val nodes = ArrayList<JsonNode>()
        while (nextToken() != JsonToken.END_ARRAY) {
            nodes.add(readValueAsTree())
        }
        return nodes
    }

    private fun JsonParser.readObject(key: SnapshotKey, field: String): JsonNode {
        if (nextToken() != JsonToken.START_OBJECT) {
            malformed(key, "[$field] is not an object")
        }
        return readValueAsTree()
    }

    private fun malformed(key: SnapshotKey, reason: String): Nothing =
        throw SnapshotFormatException("Snapshot document [$key] is malformed: $reason.")

    companion object {
        const val SCHEMA = "schema"
        const val REVISION = "revision"
        const val PUBLISHED_AT = "publishedAt"
        const val POLICIES = "policies"
        const val APP_PERMISSION = "appPermission"
        const val GRANTS = "grants"
        private const val ID = "id"
        private const val POLICY_TYPE = "type"
        private val HEADER_FIELDS = setOf(SCHEMA, REVISION, PUBLISHED_AT)

        /**
         * The global layer without global policies. 7.0's repository answers an empty list, never an empty read, so
         * this is a loaded layer without groups, not an absent one.
         */
        val NO_GLOBAL_POLICIES: LayerInput = LayerInput.Loaded(LayerKind.GLOBAL, emptyList())
    }
}
