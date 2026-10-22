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

import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.api.context.request.SpaceId
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.permission.PermissionId
import me.ahoo.cosec.api.permission.RolePermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.DocumentState
import me.ahoo.cosec.snapshot.EntryOrigin
import me.ahoo.cosec.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.snapshot.Snapshot
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotEntry
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotOptions
import me.ahoo.cosec.snapshot.SnapshotSource
import reactor.core.publisher.Mono
import tools.jackson.core.type.TypeReference
import java.time.Instant

/**
 * 7.x data as JSON, the way Redis holds it: every policy by id, the global policy index, each app's permission and
 * each role's grants by `role` or `role@space`. Both the snapshot documents and the 7.0-style repositories are built
 * from the same JSON, so they hold the same data.
 */
class LegacyData(
    /** Policy JSON by id, in the order the document lists them (by id, RFC 0003 §7 X3). */
    val policies: Map<String, String> = emptyMap(),
    /** The ids in the global policy index, in the order the repository reads them. */
    val globalIndex: List<String> = emptyList(),
    /** App permission JSON by app id. */
    val appPermissions: Map<AppId, String> = emptyMap(),
    /** Grant JSON (an array of permission ids) by app id, then by `role` or `role@space`. */
    val grants: Map<AppId, Map<String, String>> = emptyMap(),
) {
    /** The policies document; null when no policy is stored. */
    fun policiesDocument(revision: Long = 1): SnapshotDocument? {
        if (policies.isEmpty()) {
            return null
        }
        return snapshotDocument(revision, """"policies":[${policies.values.joinToString(",")}]""")
    }

    /** The document of [appId]; null when the app has no app permission. */
    fun appDocument(appId: AppId, revision: Long = 1): SnapshotDocument? {
        val appPermission = appPermissions[appId] ?: return null
        val grants = grants[appId].orEmpty().entries.joinToString(",") { (role, json) -> """"$role":$json""" }
        return snapshotDocument(revision, """"appPermission":$appPermission,"grants":{$grants}""")
    }

    /** Stores every document in [store]. */
    fun publish(store: InMemorySnapshotDocumentStore, revision: Long = 1) {
        policiesDocument(revision)?.let { store.put(SnapshotKey.Policies, it) }
        appPermissions.keys.forEach { appId ->
            appDocument(
                appId,
                revision
            )?.let { store.put(SnapshotKey.App(appId), it) }
        }
    }

    /** Reads as 7.0's `RedisPolicyRepository` does: decoded on every read, an undecodable policy fails the read. */
    val policyRepository: PolicyRepository = object : PolicyRepository {
        override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.defer {
            Mono.just(globalIndex.map { decodePolicy(policies.getValue(it)) }.filter { it.type == PolicyType.GLOBAL })
        }

        override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.defer {
            Mono.just(policyIds.mapNotNull { id -> policies[id]?.let(::decodePolicy) })
        }

        override fun setPolicy(policy: Policy): Mono<Void> = throw UnsupportedOperationException()
    }

    /** Reads as 7.0's `RedisAppRolePermissionRepository` does. */
    val appRolePermissionRepository: AppRolePermissionRepository = object : AppRolePermissionRepository {
        override fun getAppRolePermission(
            appId: AppId,
            spaceId: SpaceId,
            roleIds: Set<RoleId>
        ): Mono<AppRolePermission> =
            Mono.defer {
                if (!SnapshotKey.App.isValidAppId(appId)) {
                    return@defer Mono.empty()
                }
                val appPermission = appPermissions[appId]?.let {
                    CoSecJsonSerializer.readValue(it, AppPermission::class.java)
                } ?: return@defer Mono.empty()
                val appGrants = grants[appId].orEmpty()
                val decoded = roleIds.associateWith { roleId ->
                    val key = if (spaceId == SpaceIdCapable.DEFAULT) roleId else "$roleId@$spaceId"
                    appGrants[key]?.let(::decodeGrants)
                }
                val rolePermissions: List<RolePermission> =
                    decoded.mapNotNull { (roleId, permissions) -> permissions?.let { RolePermissionData(roleId, it) } }
                Mono.just(AppRolePermissionData(appPermission, rolePermissions))
            }
    }

    companion object {
        private val GRANTS = object : TypeReference<Set<PermissionId>>() {}

        fun decodePolicy(json: String): Policy = CoSecJsonSerializer.readValue(json, Policy::class.java)

        fun decodeGrants(json: String): Set<PermissionId> = CoSecJsonSerializer.readValue(json, GRANTS)
    }
}

val PUBLISHED_AT: Instant = Instant.parse("2026-10-21T08:00:00Z")

/** A schema-1 document whose body holds the header fields followed by [sections]. */
fun snapshotDocument(
    revision: Long,
    sections: String,
    schema: Int = SnapshotDocument.CURRENT_SCHEMA
): SnapshotDocument =
    SnapshotDocument(
        schema,
        revision,
        PUBLISHED_AT,
        """{"schema":$schema,"revision":$revision,"publishedAt":"$PUBLISHED_AT",$sections}""".encodeToByteArray(),
    )

/** A policy as JSON. */
fun policyJson(
    id: String,
    type: String = "custom",
    statements: String = """{"name":"s0","effect":"allow","action":"*"}""",
    condition: String = """{"all":{}}""",
): String =
    """{"id":"$id","name":"$id","category":"","description":"","type":"$type","tenantId":"t","condition":$condition,"statements":[$statements]}"""

/** An app permission as JSON with one group of [permissions]. */
fun appPermissionJson(id: String, permissions: String, condition: String = """{"all":{}}"""): String =
    """{"id":"$id","condition":$condition,"groups":[{"name":"g","description":"","permissions":[$permissions]}]}"""

/** A permission as JSON. */
fun permissionJson(id: String, action: String = "*", effect: String = "allow"): String =
    """{"id":"$id","name":"$id","description":"","effect":"$effect","action":"$action"}"""

internal fun policySnapshotSource(
    store: InMemorySnapshotDocumentStore,
    options: SnapshotOptions = SnapshotOptions(revalidateInterval = java.time.Duration.ZERO),
): SnapshotSource<CompiledPolicies, CompiledApp> {
    val compiler = PolicySnapshotCompiler()
    return SnapshotSource(store, compiler.policies, compiler.app, options)
}

/** The snapshot [SnapshotSource] would install for [data], compiled directly: no threads, for property tests. */
internal fun compiledSnapshot(data: LegacyData, appId: AppId?, compiler: PolicySnapshotCompiler): PolicySnapshot {
    val policies = data.policiesDocument()?.let { document ->
        compileState { compiler.policies.compile(SnapshotKey.Policies, document) }
    } ?: DocumentState.Absent
    val app = appId?.let { id ->
        val key = SnapshotKey.App(id)
        val state = data.appDocument(id)?.let { document -> compileState { compiler.app.compile(key, document) } }
            ?: DocumentState.Absent
        snapshotEntry(key, state)
    }
    return Snapshot(snapshotEntry(SnapshotKey.Policies, policies), app)
}

@Suppress("TooGenericExceptionCaught")
private fun <T : Any> compileState(compile: () -> T): DocumentState<T> = try {
    DocumentState.Present(compile())
} catch (error: Exception) {
    DocumentState.Failed(error)
}

internal fun <T : Any> snapshotEntry(key: SnapshotKey, state: DocumentState<T>): SnapshotEntry<T> {
    val revision = if (state is DocumentState.Present) 1L else 0L
    val origin = if (state is DocumentState.Present) EntryOrigin.DOCUMENT else EntryOrigin.ABSENT
    return SnapshotEntry(key, state, revision, null, PUBLISHED_AT, origin)
}
