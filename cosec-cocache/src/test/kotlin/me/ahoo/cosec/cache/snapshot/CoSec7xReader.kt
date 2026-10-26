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

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.CacheReadException
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.ForeignFormatException
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.permission.PermissionId
import me.ahoo.cosec.api.policy.PolicyId
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.SpacedRoleId
import me.ahoo.cosec.cache.AppPermissionCache
import me.ahoo.cosec.cache.AppSpacedRoleId
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.CoSecCacheSpecs.storeOfRecord
import me.ahoo.cosec.cache.GlobalPolicyIndexCache
import me.ahoo.cosec.cache.PolicyCache
import me.ahoo.cosec.cache.RedisPolicyRepository
import me.ahoo.cosec.cache.RolePermissionCache
import me.ahoo.cosec.cache.createCache
import me.ahoo.cosec.cache.createGlobalPolicyIndex
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.util.RawValue
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.TreeMap

/**
 * Reads CoSec 7.x policy data and builds the snapshot documents that hold it (RFC 0003 §4.7), for the gate, the
 * sizing measurement and development deployments. Not a user-facing migration: phase 4 provides that.
 *
 * Values are read through 7.0's four caches ([CoSecCacheSpecs]), so their format and strict reads are 7.0's. An entry
 * those caches cannot decode (say, a matcher type this instance lacks) is not dropped: it is read again as raw JSON and
 * kept in the document under its key's id, so that only that entry fails once compiled (F4), as only the requests
 * reading it fail in 7.0. Content that is not JSON at all, or that the CoCache 6 caches cannot read (unmigrated 5.x
 * data), fails the conversion.
 *
 * Data that 7.0 fails on or ignores, and that a document cannot express, is reported as a [Conversion.problems] entry,
 * and the conversion is refused until the operator fixes it (the reader never decides for them):
 * - an id in the global policy index without content (7.0 fails every request);
 * - a policy of type `GLOBAL` outside the index (7.0 does not apply it to everyone; a document has no separate index);
 * - an undecodable policy whose place in the index disagrees with its readable type;
 * - a key whose id differs from the id in its content;
 * - grants of an app without an app permission (7.0 ignores them; a document has no place for them).
 *
 * Grants of permission ids the app does not define are only warned about: the document keeps them, and evaluation
 * fails on them exactly as 7.0 does (RFC 0002 S10a).
 */
class CoSec7xReader(
    backend: CoherenceBackend,
    private val keyPrefix: String,
    private val mapper: ObjectMapper = CoSecJsonSerializer,
) : AutoCloseable {
    /** The keys to read; [scan] finds them in Redis. */
    data class Keys(
        val policyIds: Set<PolicyId>,
        val appIds: Set<AppId>,
        val grants: Set<AppSpacedRoleId>,
    )

    /** What one read produced. Only an accepted conversion ([problems] empty) may be published. */
    class Conversion(
        val contents: List<SnapshotContent>,
        val problems: List<String>,
        val warnings: List<String>,
        /** SHA-256 of every key read and its stored bytes, in key order. */
        val inputSha256: String,
    ) {
        val accepted: Boolean get() = problems.isEmpty()

        /** The documents at [revision]; refuses a conversion with problems. */
        fun documents(revision: Long = 1, publishedAt: Instant = Instant.now()): Map<SnapshotKey, SnapshotDocument> {
            check(accepted) { "The conversion has problems: $problems" }
            return contents.associate { it.key to it.write(revision, publishedAt) }
        }
    }

    private val typedRuntime = CacheRuntime.builder().backend(backend).build()
    private val rawRuntime = CacheRuntime.builder().backend(backend).build()

    private val policyCache: PolicyCache =
        typedRuntime.createCache(CoSecCacheSpecs.policy(CoSecCacheSpecs.jacksonCodec(mapper), keyPrefix))
    private val globalPolicyIndexCache: GlobalPolicyIndexCache =
        typedRuntime.createGlobalPolicyIndex(CoSecCacheSpecs.globalPolicyIndex(keyPrefix))
    private val appPermissionCache: AppPermissionCache =
        typedRuntime.createCache(CoSecCacheSpecs.appPermission(CoSecCacheSpecs.jacksonCodec(mapper), keyPrefix))
    private val rolePermissionCache: RolePermissionCache =
        typedRuntime.createCache(CoSecCacheSpecs.rolePermission(CoSecCacheSpecs.jacksonCodec(mapper), keyPrefix))

    private val rawPolicies = rawCache(CoSecCacheSpecs.POLICY_CACHE_NAME)
    private val rawAppPermissions = rawCache(CoSecCacheSpecs.APP_PERMISSION_CACHE_NAME)
    private val rawGrants = rawCache(CoSecCacheSpecs.ROLE_PERMISSION_CACHE_NAME)

    private val treeReader = mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    /** The same storage layout as 7.0's caches, with values as stored bytes. */
    private fun rawCache(cacheName: String): Cache<String, ByteArray> = rawRuntime.create(
        CacheSpec.builder<String, ByteArray>("Raw$cacheName")
            .namespace(CoSecCacheSpecs.cacheNamespace(keyPrefix, cacheName))
            .keyPrefix("")
            .keyEncoder { it }
            .codec(RawCodec)
            .policy(CachePolicy.DEFAULT.storeOfRecord())
            .build()
    )

    private object RawCodec : Codec<ByteArray> {
        override fun encode(value: ByteArray): ByteArray = value

        override fun decode(bytes: ByteArray): ByteArray = bytes
    }

    private class Report {
        val problems = ArrayList<String>()
        val warnings = ArrayList<String>()
        val input = TreeMap<String, ByteArray>()
    }

    fun read(keys: Keys): Conversion {
        val report = Report()
        val contents = ArrayList<SnapshotContent>()
        readPolicies(keys.policyIds, report)?.let { contents.add(it) }
        val grantsByApp = keys.grants.groupBy { it.appId }
        (keys.appIds + grantsByApp.keys).sorted().forEach { appId ->
            readApp(appId, grantsByApp[appId].orEmpty(), report)?.let { contents.add(it) }
        }
        return Conversion(contents, report.problems, report.warnings, sha256(report.input))
    }

    private fun readPolicies(policyIds: Set<PolicyId>, report: Report): SnapshotContent.Policies? {
        val index = globalPolicyIndexCache[GlobalPolicyIndexCache.CACHE_KEY].orEmpty()
        report.input["global"] = index.sorted().joinToString("\n").encodeToByteArray()
        val policies = TreeMap<PolicyId, JsonNode>()
        for (id in policyIds.sorted()) {
            readPolicy(id, id in index, report)?.let { policies[id] = it }
        }
        (index - policies.keys).sorted().forEach { id ->
            if (rawPolicies[id] == null) {
                report.problems += "the global policy index lists policy [$id], which has no content"
            }
        }
        return if (policies.isEmpty()) null else SnapshotContent.Policies(policies)
    }

    /** The policy stored under [id] as it goes into the document, or null when it has no content or a problem. */
    private fun readPolicy(id: PolicyId, inIndex: Boolean, report: Report): JsonNode? {
        val raw = rawPolicies[id] ?: return null
        report.input["policy:$id"] = raw
        val node = objectOf(raw, "policy [$id]", report) ?: return null
        val named = node.hasNonNull(ID)
        if (!keepKeyId(node, id, "policy", report)) {
            return null
        }
        val policy = decoded("policy [$id]", report) { policyCache[id] }
        if (policy != null) {
            if (policy.type == PolicyType.GLOBAL && !inIndex) {
                report.problems += "policy [$id] is of type GLOBAL but not in the global policy index, " +
                    "so 7.0 does not apply it to everyone"
            }
        } else if (mayBeGlobal(node) != inIndex) {
            report.problems += "policy [$id] cannot be decoded and its readable type " +
                "disagrees with the global policy index (indexed: $inIndex)"
        }
        return if (named) stored(raw) else node
    }

    private fun readApp(appId: AppId, grantIds: List<AppSpacedRoleId>, report: Report): SnapshotContent.App? {
        val raw = rawAppPermissions[appId]
        if (raw == null) {
            if (grantIds.isNotEmpty()) {
                report.problems += "app [$appId] has grants ${grantIds.map { it.toString() }.sorted()} " +
                    "but no app permission, so 7.0 ignores them"
            }
            return null
        }
        report.input["app:permission:$appId"] = raw
        val node = objectOf(raw, "the app permission of [$appId]", report) ?: return null
        val named = node.hasNonNull(ID)
        if (!keepKeyId(node, appId, "app permission", report)) {
            return null
        }
        val appPermission = decoded("the app permission of [$appId]", report) { appPermissionCache[appId] }
        val defined = appPermission?.definedPermissionIds()
        val grants = TreeMap<String, JsonNode>()
        for (grantId in grantIds.sortedBy { it.toString() }) {
            readGrant(grantId, defined, report)?.let { grants[grantKeyOf(grantId)] = it }
        }
        return SnapshotContent.App(SnapshotKey.App(appId), if (named) stored(raw) else node, grants)
    }

    /** The grants stored under [grantId] as they go into the document, or null when they have none or a problem. */
    private fun readGrant(grantId: AppSpacedRoleId, defined: Set<PermissionId>?, report: Report): JsonNode? {
        val rawGrant = rawGrants[grantId.toString()] ?: return null
        report.input["app:role:permission:$grantId"] = rawGrant
        val grantKey = grantKeyOf(grantId)
        if (!SnapshotContent.isValidGrantKey(grantKey)) {
            report.problems += "the grants of [$grantId] are keyed [$grantKey], which names no role in a space: " +
                "7.0 never applies them, and a document cannot hold them"
            return null
        }
        treeOf(rawGrant, "the grants of [$grantId]", report) ?: return null
        val permissionIds = decoded("the grants of [$grantId]", report) { rolePermissionCache[grantId] }
        if (defined != null && permissionIds != null) {
            val unresolved = permissionIds - defined - AppRolePermission.ALL_PERMISSION_ID
            if (unresolved.isNotEmpty()) {
                report.warnings += "the grants of [$grantId] name permissions ${unresolved.sorted()} " +
                    "that app [${grantId.appId}] does not define; evaluation fails on them, as in 7.0"
            }
        }
        return stored(rawGrant)
    }

    private fun AppPermission.definedPermissionIds(): Set<PermissionId> =
        groups.flatMapTo(HashSet()) { group -> group.permissions.map { it.id } }

    private fun grantKeyOf(grantId: AppSpacedRoleId): String =
        if (grantId.spaceId == SpaceIdCapable.DEFAULT) {
            grantId.roleId
        } else {
            "${grantId.roleId}${SpacedRoleId.SPACE_ID_SEPARATOR}${grantId.spaceId}"
        }

    /**
     * The value [read] decodes through 7.0's cache, or null (with a warning) when it cannot be decoded. Content that
     * is not CoCache 6's (format 0, or written by a newer version) is not an entry problem: it fails the conversion.
     */
    private inline fun <T : Any> decoded(what: String, report: Report, read: () -> T?): T? = try {
        read()
    } catch (error: CacheReadException) {
        val foreign = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<ForeignFormatException>()
            .firstOrNull()
        if (foreign == null || foreign.format == 0 || foreign.newer) {
            throw error
        }
        report.warnings += "$what cannot be decoded (${error.rootCauseMessage()}): kept as raw JSON, " +
            "so only the layers reading it fail"
        null
    }

    private fun Throwable.rootCauseMessage(): String? = generateSequence(this) { it.cause }.last().message

    /** The stored bytes as they are, so the document holds exactly what 7.0 stored. */
    private fun stored(raw: ByteArray): JsonNode = JsonNodeFactory.instance.rawValueNode(RawValue(raw.decodeToString()))

    /** [raw] as a JSON object, or null with a problem. */
    private fun objectOf(raw: ByteArray, what: String, report: Report): ObjectNode? {
        val node = treeOf(raw, what, report) ?: return null
        if (node !is ObjectNode) {
            report.problems += "$what is not a JSON object"
            return null
        }
        return node
    }

    private fun treeOf(raw: ByteArray, what: String, report: Report): JsonNode? = try {
        treeReader.readTree(raw)
    } catch (_: JacksonException) {
        report.problems += "$what is not valid JSON"
        null
    }

    /**
     * Gives [node] the id its key names when it has none, and reports a content id that differs from the key's: the
     * compiler indexes a policy by its content id, 7.0 by its key.
     */
    private fun keepKeyId(node: ObjectNode, keyId: String, kind: String, report: Report): Boolean {
        val id = node.get(ID)
        if (id == null || id.isNull) {
            node.put(ID, keyId)
            return true
        }
        if (!id.isString || id.asString() != keyId) {
            report.problems += "$kind [$keyId] holds content with id [$id]"
            return false
        }
        return true
    }

    /** The compiler's rule: a policy is global unless its type readably says otherwise. */
    @Suppress("TooGenericExceptionCaught")
    private fun mayBeGlobal(node: JsonNode): Boolean {
        val type = node.get("type") ?: return true
        return try {
            (mapper.treeToValue(type, PolicyType::class.java) ?: PolicyType.GLOBAL) == PolicyType.GLOBAL
        } catch (_: Exception) {
            true
        }
    }

    private fun sha256(input: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.forEach { (key, value) ->
            val keyBytes = key.encodeToByteArray()
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(keyBytes.size).array())
            digest.update(keyBytes)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
            digest.update(value)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun close() {
        rawRuntime.close()
        typedRuntime.close()
    }

    companion object {
        private const val ID = "id"

        /**
         * The keys of 7.x data under [keyPrefix], found by [scan] (a SCAN with a MATCH pattern, all keys it returns).
         * The policy history (`<p>:policy:history:*`) is skipped, as `CoSecSchemaMarker` does.
         */
        fun keys(keyPrefix: String, scan: (String) -> Sequence<String>): Keys {
            val policyPrefix = CoSecCacheSpecs.cacheNamespace(keyPrefix, CoSecCacheSpecs.POLICY_CACHE_NAME) + ":"
            val appPrefix = CoSecCacheSpecs.cacheNamespace(keyPrefix, CoSecCacheSpecs.APP_PERMISSION_CACHE_NAME) + ":"
            val grantPrefix = CoSecCacheSpecs.cacheNamespace(keyPrefix, CoSecCacheSpecs.ROLE_PERMISSION_CACHE_NAME) + ":"
            val policyIds = scan("$policyPrefix*").map { it.removePrefix(policyPrefix) }
                .filterNot { it.startsWith(RedisPolicyRepository.RESERVED_POLICY_ID_PREFIX) }
                .toSortedSet()
            val appIds = scan("$appPrefix*").map { it.removePrefix(appPrefix) }.toSortedSet()
            val grants = scan("$grantPrefix*").map { it.removePrefix(grantPrefix) }.map(::parseGrantKey).toSet()
            return Keys(policyIds, appIds, grants)
        }

        /** `<appId>:<roleId>[@<spaceId>]`, as [AppSpacedRoleId.toString] writes it. */
        fun parseGrantKey(key: String): AppSpacedRoleId {
            val appSeparator = key.indexOf(AppSpacedRoleId.APP_ID_SEPARATOR)
            require(appSeparator > 0) { "Grant key [$key] has no app id." }
            val role = key.substring(appSeparator + 1)
            val spaceSeparator = role.indexOf(SpacedRoleId.SPACE_ID_SEPARATOR)
            return if (spaceSeparator < 0) {
                AppSpacedRoleId(key.substring(0, appSeparator), role)
            } else {
                AppSpacedRoleId(
                    key.substring(0, appSeparator),
                    role.substring(0, spaceSeparator),
                    role.substring(spaceSeparator + 1)
                )
            }
        }
    }
}
