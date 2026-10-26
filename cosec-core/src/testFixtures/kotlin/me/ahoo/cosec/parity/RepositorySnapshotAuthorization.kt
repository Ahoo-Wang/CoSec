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
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.permission.RolePermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyId
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.authorization.SnapshotEngineAuthorization
import me.ahoo.cosec.authorization.engine.AppPermissionRules
import me.ahoo.cosec.authorization.engine.Compiled
import me.ahoo.cosec.authorization.engine.CompiledApp
import me.ahoo.cosec.authorization.engine.CompiledPolicies
import me.ahoo.cosec.authorization.engine.LayerReads
import me.ahoo.cosec.authorization.engine.LegacyFacts
import me.ahoo.cosec.authorization.engine.PolicyGroup
import me.ahoo.cosec.authorization.engine.PolicySnapshotCompiler
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.engine.LayerInput
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.snapshot.SnapshotChangeListener
import me.ahoo.cosec.snapshot.SnapshotDigest
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotMetrics
import me.ahoo.cosec.snapshot.SnapshotOptions
import me.ahoo.cosec.snapshot.SnapshotRead
import me.ahoo.cosec.snapshot.SnapshotSource
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * The snapshot read path (RFC 0003 §5.1) over what repositories answer, so that the decision tests of §8.2, which hold
 * their data in repositories (often mocked ones), run against the snapshot source too.
 *
 * Every decision gets a [SnapshotSource] of its own, whose documents hold exactly what the repositories answer for that
 * request: the global policies, the principal's policies and the app's permission with the grants of the roles in the
 * request's space. From there on it is the real path: [SnapshotSource] loads and installs the documents, and
 * [SnapshotEngineAuthorization] evaluates them through [me.ahoo.cosec.authorization.engine.SnapshotPolicySetLoader].
 * Only the JSON compilation is left out, since mocked policies have no JSON: P5, the `PolicyTestKit` suites and the
 * replay cover it ([PolicySnapshotCompiler]).
 *
 * The repositories' failures become what the same failure of a snapshot document becomes (RFC 0003 §4.4): a failed
 * global read makes the global layer unavailable, a failed read of the principal's policies makes each of them broken,
 * and a failed app read fails the app document. The error then reaches the caller as a
 * [me.ahoo.cosec.snapshot.SnapshotUnavailableException] caused by it (§7 X4).
 */
internal class RepositorySnapshotAuthorization(
    private val policyRepository: PolicyRepository,
    private val appRolePermissionRepository: AppRolePermissionRepository,
    private val blacklistChecker: BlacklistChecker,
    private val rootPrincipalMatcher: RootPrincipalMatcher,
) : DecisionAuthorization {
    override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> = Mono.using(
        { RequestSnapshot(request, context) },
        { SnapshotEngineAuthorization(it.source, blacklistChecker, rootPrincipalMatcher).decide(request, context) },
        { it.close() },
    )

    /** The documents of one request, read from the repositories when the source loads them. */
    private inner class RequestSnapshot(
        request: Request,
        context: SecurityContext
    ) : SnapshotDocumentStore, AutoCloseable {
        /** Read lazily: only an evaluation that needs policies reads anything, as the repository loader. */
        private val reads by lazy { LayerReads.of(LegacyFacts.authzRequest(request, context).host as LegacyFacts) }

        @Volatile
        private var policies: CompiledPolicies? = null

        @Volatile
        private var app: CompiledApp? = null

        val source: SnapshotSource<CompiledPolicies, CompiledApp> = SnapshotSource(
            this,
            { _, _ -> checkNotNull(policies) },
            { _, _ -> checkNotNull(app) },
            OPTIONS,
            Clock.systemUTC(),
            SnapshotMetrics.NOOP,
            COMPILE_EXECUTOR,
            SCHEDULER,
            System::nanoTime,
        )

        /** Each document is read once: the source confirms what it read with a conditional read. */
        private val answers = ConcurrentHashMap<SnapshotKey, CompletableFuture<SnapshotRead>>()

        override fun read(key: SnapshotKey): CompletableFuture<SnapshotRead> = answers.computeIfAbsent(key, ::readNow)

        private fun readNow(key: SnapshotKey): CompletableFuture<SnapshotRead> = when (key) {
            SnapshotKey.Policies -> readPolicies().map<SnapshotRead> { present(it) }
            is SnapshotKey.App -> readApp(key).map<SnapshotRead> { present(it) }.defaultIfEmpty(SnapshotRead.Absent)
        }.toFuture().thenApply { checkNotNull(it) }

        private fun present(compiled: Any): SnapshotRead {
            when (compiled) {
                is CompiledPolicies -> policies = compiled
                is CompiledApp -> app = compiled
            }
            return SnapshotRead.Present(PLACEHOLDER, PLACEHOLDER_DIGEST)
        }

        private fun readPolicies(): Mono<CompiledPolicies> {
            val principalIds = if (reads.principalLayer == null) reads.policyIds else emptySet()
            val principal: Mono<Answer<List<Policy>>> = when {
                principalIds.isEmpty() -> Mono.just(Answer.Empty)
                else -> answer { policyRepository.getPolicies(principalIds) }
            }
            return Mono.zip(answer { policyRepository.getGlobalPolicy() }, principal) { global, answered ->
                CompiledPolicies(1, globalLayer(global), byId(principalIds, answered))
            }
        }

        private fun globalLayer(global: Answer<List<Policy>>): LayerInput = when (global) {
            is Answer.Value -> LayerInput.Loaded(LayerKind.GLOBAL, global.value.map(::groupOf))
            Answer.Empty -> PolicySnapshotCompiler.NO_GLOBAL_POLICIES
            is Answer.Failure -> LayerInput.Unavailable(LayerKind.GLOBAL, global.error)
        }

        private fun byId(requested: Set<PolicyId>, principal: Answer<List<Policy>>): Map<PolicyId, Compiled<PolicyGroup>> =
            when (principal) {
                is Answer.Value -> buildMap { principal.value.forEach { putIfAbsent(it.id, Compiled.Ok(groupOf(it))) } }
                Answer.Empty -> emptyMap()
                // 7.0 reads the principal's policies at once: each of them is as unreadable as the read.
                is Answer.Failure -> requested.associateWith { Compiled.Broken(principal.error) }
            }

        private fun readApp(key: SnapshotKey.App): Mono<CompiledApp> = Mono.defer {
            appRolePermissionRepository.getAppRolePermission(key.appId, reads.spaceId, reads.scopedRoles)
        }.map { compiledApp(key, it) }

        private fun compiledApp(key: SnapshotKey.App, answer: AppRolePermission): CompiledApp {
            val grants = LinkedHashMap<RoleId, Compiled<RolePermission>>()
            answer.rolePermissions.forEach { grants.putIfAbsent(it.id, Compiled.Ok(it)) }
            return CompiledApp(1, key.appId, Compiled.Ok(rulesOf(answer.appPermission)), mapOf(reads.spaceId to grants))
        }

        // Nothing changes during one request: a known document is unchanged, so the repositories are read once.
        override fun readIfChanged(key: SnapshotKey, known: SnapshotDigest?): CompletableFuture<SnapshotRead> =
            if (known == PLACEHOLDER_DIGEST) CompletableFuture.completedFuture(SnapshotRead.Unchanged) else read(key)

        override fun subscribe(listener: SnapshotChangeListener): AutoCloseable = AutoCloseable {}

        override fun close() {
            source.close()
        }
    }

    /** What a repository read answered; a failure is a value, so it fails only the layer that reads it. */
    private sealed interface Answer<out T> {
        class Value<T>(val value: T) : Answer<T>

        data object Empty : Answer<Nothing>

        class Failure(val error: Throwable) : Answer<Nothing>
    }

    private fun <T : Any> answer(read: () -> Mono<T>): Mono<Answer<T>> = Mono.defer(read)
        .map<Answer<T>> { Answer.Value(it) }
        .defaultIfEmpty(Answer.Empty)
        .onErrorResume { Mono.just(Answer.Failure(it)) }

    private companion object {
        private val OPTIONS = SnapshotOptions(revalidateInterval = Duration.ZERO)
        private val PLACEHOLDER = SnapshotDocument(SnapshotDocument.CURRENT_SCHEMA, 1, PUBLISHED_AT, ByteArray(0))
        private val PLACEHOLDER_DIGEST = InMemorySnapshotDocumentStore.digestOf(PLACEHOLDER)

        /** Shared by every request's source: a source never shuts down executors it was given. */
        private val COMPILE_EXECUTOR = Executors.newFixedThreadPool(2, daemon("cosec-snapshot-compile-test-"))
        private val SCHEDULER: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor(daemon("cosec-snapshot-scheduler-test-"))

        private fun daemon(prefix: String): ThreadFactory {
            val counter = AtomicInteger()
            return ThreadFactory { Thread(it, prefix + counter.incrementAndGet()).apply { isDaemon = true } }
        }

        fun groupOf(policy: Policy): PolicyGroup =
            if (policy is PolicyData) policy.engineGroup() else PolicyGroup(policy)

        fun rulesOf(appPermission: AppPermission): AppPermissionRules =
            if (appPermission is AppPermissionData) appPermission.engineRules() else AppPermissionRules(appPermission)
    }
}
