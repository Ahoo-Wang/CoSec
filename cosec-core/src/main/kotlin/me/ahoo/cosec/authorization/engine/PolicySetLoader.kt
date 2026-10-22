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

import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.MissingPolicyException
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RoleScope
import me.ahoo.cosec.engine.AbsentReason
import me.ahoo.cosec.engine.LayerInput
import me.ahoo.cosec.engine.PolicySet
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Cause
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.policy.PolicyData
import reactor.core.Exceptions
import reactor.core.Fuseable
import reactor.core.publisher.Mono

/**
 * Reads the three layers of a request in parallel and adapts them (RFC 0002 §3.3, X1).
 *
 * Every layer is always present, in the order GLOBAL, PRINCIPAL, ROLE: a layer without data is [LayerInput.Absent].
 * A failed read becomes [LayerInput.Unavailable] instead of an error signal, so it fails the request only when the
 * evaluation reaches that layer, as 7.0's layer-by-layer reads do. The lists handed to the engine are never changed
 * after they are built.
 */
internal class PolicySetLoader(
    private val policyRepository: PolicyRepository,
    private val appRolePermissionRepository: AppRolePermissionRepository,
) {
    /**
     * The request's layers. A repository that answers with a scalar [Mono] (`just`, `empty`, `error`, as in-memory
     * repositories do) is read synchronously, without subscribing; only the others are subscribed, all at once
     * through `Mono.zip`. The Redis repositories always answer through `Mono.defer`, even on a local cache hit, so
     * production takes the subscribed path. Either way a layer is adapted, and a failed read becomes
     * [LayerInput.Unavailable], the same way.
     */
    fun load(request: AuthzRequest): Mono<PolicySet> {
        val reads = LayerReads.of(request.host as LegacyFacts)
        val global = global()
        val principal = reads.principalLayer ?: principal(reads.policyIds)
        val role = reads.roleLayer ?: role(reads)
        if (global is LayerInput && principal is LayerInput) {
            if (role is LayerInput) {
                return Mono.just(PolicySet(listOf(global, principal, role)))
            }
            // Only the role layer answers asynchronously, as when its grants are read per request: no zip needed.
            return role.asMono().map { PolicySet(listOf(global, principal, it)) }
        }
        return Mono.zip(
            { layers -> PolicySet(listOf(layers[0] as LayerInput, layers[1] as LayerInput, layers[2] as LayerInput)) },
            global.asMono(),
            principal.asMono(),
            role.asMono(),
        )
    }

    /** A [LayerInput], or a [Mono] of one. */
    @Suppress("UNCHECKED_CAST")
    private fun Any.asMono(): Mono<LayerInput> = if (this is LayerInput) Mono.just(this) else this as Mono<LayerInput>

    private fun global(): Any = layer(
        LayerKind.GLOBAL,
        read = { policyRepository.getGlobalPolicy() },
        empty = { NO_GLOBAL_POLICIES },
        adapt = { policies -> LayerInput.Loaded(LayerKind.GLOBAL, groupsOf(policies)) },
    )

    /** The principal's policies, when [LayerReads] found some to read. */
    private fun principal(policyIds: Set<String>): Any = layer(
        LayerKind.PRINCIPAL,
        read = { policyRepository.getPolicies(policyIds) },
        // An empty read means none was found.
        empty = { principalLayer(policyIds, emptyList()) },
        adapt = { policies -> principalLayer(policyIds, policies) },
    )

    /**
     * [PolicyRepository.getPolicies] returns only the policies it found. A missing one may be the deny that keeps
     * the request out, so the layer fails when it is reached (D2). A nested scan: principals list few policies.
     */
    private fun principalLayer(policyIds: Set<String>, policies: List<Policy>): LayerInput {
        for (policyId in policyIds) {
            if (policies.none { it.id == policyId }) {
                val missing = policyIds - policies.map { it.id }.toSet()
                val cause = Cause.MissingPolicies(missing, MissingPolicyException(missing))
                return LayerInput.Loaded(LayerKind.PRINCIPAL, emptyList(), cause)
            }
        }
        return LayerInput.Loaded(LayerKind.PRINCIPAL, groupsOf(policies))
    }

    /**
     * The layer's groups, adapted from a snapshot of [policies] taken now, as 7.0's `filter` took one: a repository
     * list changed later can never pair one policy's guard with another's rules.
     */
    private fun groupsOf(policies: List<Policy>): List<PolicyGroup> = policies.map { policy ->
        if (policy is PolicyData) policy.engineGroup() else PolicyGroup(policy)
    }

    /**
     * Only the principal's roles scoped to the request's space count (see [RoleScope]); the repository keys their
     * grants by the request's app, so a grant never applies in another app or space. [LayerReads] read the roles, the
     * space and the app; the repository gets the app id as read, valid or not, as in 7.0.
     */
    private fun role(reads: LayerReads): Any {
        val appId = checkNotNull(reads.appId)
        return layer(
            LayerKind.ROLE,
            read = { appRolePermissionRepository.getAppRolePermission(appId, reads.spaceId, reads.scopedRoles) },
            empty = { NO_APP_PERMISSION },
            adapt = { appRolePermission ->
                val group =
                    AppPermissionGroup(rulesOf(appRolePermission.appPermission), appRolePermission.rolePermissions)
                LayerInput.Loaded(LayerKind.ROLE, listOf(group))
            },
        )
    }

    /**
     * Reads one layer: the [LayerInput] when [read] answers synchronously, else a [Mono] of it. Whatever [read] or
     * [adapt] throws, synchronously or as an error signal, makes the layer [LayerInput.Unavailable], so it fails the
     * request only if the evaluation reaches it. JVM-fatal errors propagate, as Reactor's operators let them.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T : Any> layer(
        kind: LayerKind,
        read: () -> Mono<T>,
        crossinline empty: () -> LayerInput,
        crossinline adapt: (T) -> LayerInput,
    ): Any {
        val source = try {
            read()
        } catch (error: Throwable) {
            return unavailable(kind, error)
        }
        if (source !is Fuseable.ScalarCallable<*>) {
            return source.map { adapt(it) }
                .switchIfEmpty(Mono.fromSupplier { empty() })
                .onErrorResume { Mono.just(LayerInput.Unavailable(kind, it)) }
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            val value = source.call() as T?
            if (value == null) empty() else adapt(value)
        } catch (error: Throwable) {
            unavailable(kind, Exceptions.unwrap(error))
        }
    }

    private fun unavailable(kind: LayerKind, error: Throwable): LayerInput {
        Exceptions.throwIfJvmFatal(error)
        return LayerInput.Unavailable(kind, error)
    }

    private fun rulesOf(appPermission: AppPermission): AppPermissionRules =
        if (appPermission is AppPermissionData) appPermission.engineRules() else AppPermissionRules(appPermission)

    companion object {
        private val NO_GLOBAL_POLICIES: LayerInput = LayerInput.Absent(LayerKind.GLOBAL, AbsentReason.NO_POLICIES)
        private val NO_APP_PERMISSION: LayerInput = LayerInput.Absent(LayerKind.ROLE, AbsentReason.NO_APP_PERMISSION)

        /** Whether any matcher of [policies] may block, so the evaluation has to leave the caller's thread (X2). */
        fun requiresBlocking(policies: PolicySet): Boolean {
            for (layer in policies.layers) {
                if (layer !is LayerInput.Loaded || layer.missing != null) {
                    continue
                }
                for (group in layer.groups) {
                    val blocks = when (group) {
                        is PolicyGroup -> group.requiresBlocking
                        is AppPermissionGroup -> group.requiresBlocking
                        else -> true
                    }
                    if (blocks) {
                        return true
                    }
                }
            }
            return false
        }
    }
}
