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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cocache.api.CacheWriteException
import me.ahoo.cocache.api.ConditionalGet
import me.ahoo.cocache.api.ConditionalPut
import me.ahoo.cocache.api.spi.Digest
import me.ahoo.cosec.CoSecException
import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.api.context.request.SpaceId
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.PermissionId
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.api.principal.SpacedRoleId
import me.ahoo.cosec.authorization.RoleScope
import me.ahoo.cosec.cache.AppSpacedRoleId
import me.ahoo.cosec.cache.RedisPolicyRepository
import me.ahoo.cosec.permission.DefaultAppPermissionEvaluator
import me.ahoo.cosec.policy.DefaultPolicyEvaluator
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotPublisher
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Clock
import java.time.Duration
import java.util.TreeMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * The [SnapshotPublisher] over CoCache and Redis (RFC 0003 §4.6): every write reads the document from Redis with a
 * conditional read (bypassing local copies), changes it, and writes it back with `putIfMatch`, which writes only if
 * Redis still holds the digest that was read, in one Lua script.
 *
 * On [ConditionalPut.Conflict] the write starts over from a fresh read, never from the conflict's digest: that digest
 * may belong to a value this instance cannot decode, and writing over it would replace a document nobody has seen.
 * A value that cannot be decoded therefore fails the write (the re-read throws) and stays in Redis for the operator.
 * Attempts after a conflict wait a random time up to an exponentially growing bound ([retryBackoff], at most
 * [maxRetryBackoff]), so writers that keep colliding, such as every instance starting at once, spread out.
 *
 * A write that would leave the document as it is (the same policy again, a grant that is already there) writes
 * nothing: no revision, no invalidation, no reload on any instance. Batches ([setPolicies], the map form of
 * [setRoleGrants]) change a document once for many entries.
 *
 * A write is validated as 7.0 validates it ([DefaultPolicyEvaluator], [DefaultAppPermissionEvaluator]). The other
 * entries of the document are written back as they were read (see [SnapshotContent]).
 *
 * After a write, [onPublished] is told the key (typically [RedisSnapshotDocumentStore.notifyChanged]), so this instance
 * reloads at once instead of waiting for the invalidation to come back from Redis.
 *
 * Redis replicates asynchronously, so a failover can lose a write that was acknowledged here. The publisher does not
 * wait for replicas (RFC 0003 §4.6 explains why): the lost write shows up as a revision regression, which every
 * [me.ahoo.cosec.snapshot.SnapshotSource] reports (F11), and the next write is based on what Redis then holds.
 */
class RedisSnapshotPublisher(
    private val cache: PolicySnapshotCache,
    private val onPublished: (SnapshotKey) -> Unit = {},
    private val mapper: ObjectMapper = CoSecJsonSerializer,
    private val clock: Clock = Clock.systemUTC(),
    /** Read-modify-write attempts before a contended write gives up. */
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /** Where a document is changed and serialized: CPU work that grows with the document, kept off the caller. */
    private val scheduler: Scheduler = Schedulers.boundedElastic(),
    /** The bound of the first wait after a conflict; it doubles with every further conflict. */
    private val retryBackoff: Duration = DEFAULT_RETRY_BACKOFF,
    private val maxRetryBackoff: Duration = DEFAULT_MAX_RETRY_BACKOFF,
) : SnapshotPublisher {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive." }
        require(!retryBackoff.isNegative && retryBackoff <= maxRetryBackoff) {
            "retryBackoff must not be negative and must not exceed maxRetryBackoff."
        }
    }

    constructor(cache: PolicySnapshotCache, store: RedisSnapshotDocumentStore) : this(cache, store::notifyChanged)

    private val async = cache.async()
    private val conflictCount = AtomicLong()

    /** Writes that lost a race and started over; for tests and diagnostics. */
    val conflicts: Long get() = conflictCount.get()

    override fun setPolicy(policy: Policy): Mono<Void> = setPolicies(listOf(policy))

    override fun setPolicies(policies: Collection<Policy>): Mono<Void> = Mono.defer {
        val ids = policies.map { it.id }
        require(ids.size == ids.toSet().size) { "Policies $ids are listed more than once." }
        if (policies.isEmpty()) {
            return@defer Mono.empty()
        }
        update(SnapshotKey.Policies) { current ->
            log.info { "setPolicies - policies: $ids." }
            val stored = (current as SnapshotContent.Policies?)?.policies ?: TreeMap()
            for (policy in policies) {
                require(!policy.id.startsWith(RedisPolicyRepository.RESERVED_POLICY_ID_PREFIX)) {
                    "Policy id [${policy.id}] must not start with [${RedisPolicyRepository.RESERVED_POLICY_ID_PREFIX}], " +
                        "which holds the policy history."
                }
                DefaultPolicyEvaluator.evaluate(policy)
                stored[policy.id] = treeOf(policy, Policy::class.java)
            }
            SnapshotContent.Policies(stored)
        }
    }

    override fun setAppPermission(appPermission: AppPermission): Mono<Void> = Mono.defer {
        val key = SnapshotKey.App(appPermission.id)
        update(key) { current ->
            DefaultAppPermissionEvaluator.evaluate(appPermission)
            val grants = (current as SnapshotContent.App?)?.grants ?: TreeMap()
            SnapshotContent.App(key, treeOf(appPermission, AppPermission::class.java), grants)
        }
    }

    override fun setRoleGrants(
        appId: AppId,
        roleId: RoleId,
        spaceId: SpaceId,
        permissionIds: Set<PermissionId>
    ): Mono<Void> = setRoleGrants(appId, mapOf(SpacedRoleId(roleId, spaceId) to permissionIds))

    override fun setRoleGrants(appId: AppId, grants: Map<SpacedRoleId, Set<PermissionId>>): Mono<Void> = Mono.defer {
        val keyed = grants.mapKeys { (role, _) -> grantKey(appId, role.roleId, role.spaceId) }
        if (keyed.isEmpty()) {
            return@defer Mono.empty()
        }
        update(SnapshotKey.App(appId)) { current ->
            val app = current as SnapshotContent.App?
                ?: throw IllegalStateException(
                    "App [$appId] has no app permission: write it before its grants, which would not apply without it."
                )
            keyed.forEach { (grantKey, permissionIds) ->
                val node = JsonNodeFactory.instance.arrayNode()
                permissionIds.sorted().forEach { node.add(it) }
                app.grants[grantKey] = node
            }
            app
        }
    }

    override fun removeRoleGrants(appId: AppId, roleId: RoleId, spaceId: SpaceId): Mono<Void> = Mono.defer {
        val grantKey = grantKey(appId, roleId, spaceId)
        update(SnapshotKey.App(appId)) { current ->
            (current as SnapshotContent.App?)?.takeIf { it.grants.remove(grantKey) != null }
        }
    }

    /**
     * Replaces the document of [content]'s key with [content], at the next revision. For tools that build whole
     * documents, such as the 7.x converter.
     */
    fun replace(content: SnapshotContent): Mono<Void> = update(content.key) { content }

    /** [value] as 7.0's cache codec writes it: serialized as [type], not as its runtime class. */
    private fun <T : Any> treeOf(value: T, type: Class<T>): JsonNode =
        mapper.readTree(mapper.writerFor(type).writeValueAsBytes(value))

    /**
     * The key of a grant inside its app document: the checks of the 7.0 Redis key, and a key the compiler reads back
     * as this role in this space. A space containing `@` would make `role@a@b`, which the compiler rejects: one such
     * grant would fail every request of the app, where 7.0 only left it unused.
     */
    private fun grantKey(appId: AppId, roleId: RoleId, spaceId: SpaceId): String {
        AppSpacedRoleId(appId, roleId, spaceId)
        val key = if (spaceId == SpaceIdCapable.DEFAULT) roleId else "$roleId${SpacedRoleId.SPACE_ID_SEPARATOR}$spaceId"
        require(RoleScope.parse(key) == SpacedRoleId(roleId, spaceId)) {
            "Space id [$spaceId] of role [$roleId] must not contain '${SpacedRoleId.SPACE_ID_SEPARATOR}'."
        }
        return key
    }

    /**
     * The read-modify-write loop. [change] gets the current content (null when there is no document) and returns the
     * content to write, or null to write nothing.
     */
    private fun update(key: SnapshotKey, change: (SnapshotContent?) -> SnapshotContent?): Mono<Void> =
        tryWrite(key, change, 1).then()

    /**
     * One attempt. Every conflict means another writer's write landed, so with fewer than [maxAttempts] concurrent
     * writers of one document, each of them gets through.
     */
    private fun tryWrite(key: SnapshotKey, change: (SnapshotContent?) -> SnapshotContent?, attempt: Int): Mono<Unit> =
        Mono.fromFuture { async.getIfChanged(key, null) }
            .publishOn(scheduler)
            .flatMap { read ->
                val current = (read as? ConditionalGet.Present)?.value
                val expected = (read as? ConditionalGet.Present)?.digest
                val content = change(current?.let { SnapshotContent.read(key, it) })
                    ?: return@flatMap Mono.just(Unit)
                if (current != null && unchanged(content, current)) {
                    return@flatMap Mono.just(Unit)
                }
                val next = content.write(current?.revision?.plus(1) ?: 1, clock.instant(), mapper)
                write(key, expected, next).flatMap { written ->
                    when {
                        written -> {
                            onPublished(key)
                            Mono.just(Unit)
                        }

                        attempt >= maxAttempts -> Mono.error(
                            SnapshotPublishConflictException(key, attempt)
                        )

                        else -> Mono.delay(backoff(attempt), scheduler).then(tryWrite(key, change, attempt + 1))
                    }
                }
            }

    /** Whether [content] is what [current] already holds: written with [current]'s header, the bytes are the same. */
    private fun unchanged(content: SnapshotContent, current: SnapshotDocument): Boolean =
        content.write(current.revision, current.publishedAt, mapper).bodyBytes().contentEquals(current.bodyBytes())

    /** A random wait up to [retryBackoff] doubled for every conflict after the first, at most [maxRetryBackoff]. */
    private fun backoff(attempt: Int): Duration {
        val bound = retryBackoff.toNanos().shl((attempt - 1).coerceAtMost(MAX_BACKOFF_SHIFT))
            .coerceIn(0, maxRetryBackoff.toNanos())
        return Duration.ofNanos(jitter(bound))
    }

    /** A wait in nanoseconds in `[0, bound]`; tests replace it. */
    internal var jitter: (Long) -> Long = { bound -> ThreadLocalRandom.current().nextLong(bound + 1) }

    /**
     * Writes [next] if Redis holds [expected]; true when written, false on a conflict. A write that fails with an
     * unknown outcome (a timeout after Redis applied it) is checked by reading the digest back: `putIfMatch` is not
     * idempotent, so it is never just repeated.
     */
    private fun write(key: SnapshotKey, expected: Digest?, next: SnapshotDocument): Mono<Boolean> =
        Mono.fromFuture { async.putIfMatch(key, expected, next) }
            .map { result ->
                when (result) {
                    is ConditionalPut.Written -> true
                    is ConditionalPut.Conflict -> {
                        conflictCount.incrementAndGet()
                        log.debug { "Snapshot document [$key] changed while it was written; reading it again." }
                        false
                    }
                }
            }
            .onErrorResume(CacheWriteException::class.java) { error ->
                val mine = Digest(RedisSnapshotDocumentStore.digestOf(next).toByteArray())
                Mono.fromFuture { async.getIfChanged(key, mine) }
                    .onErrorMap { error }
                    .flatMap { read -> if (read == ConditionalGet.Unchanged) Mono.just(true) else Mono.error(error) }
            }

    companion object {
        private val log = KotlinLogging.logger {}
        const val DEFAULT_MAX_ATTEMPTS = 64
        val DEFAULT_RETRY_BACKOFF: Duration = Duration.ofMillis(5)
        val DEFAULT_MAX_RETRY_BACKOFF: Duration = Duration.ofMillis(200)

        /** Keeps the shifted bound from overflowing; any bound this large is capped by [maxRetryBackoff] anyway. */
        private const val MAX_BACKOFF_SHIFT = 20
    }
}

/** A write lost the race for [key] [attempts] times in a row: other writers keep changing the document. */
class SnapshotPublishConflictException(val key: SnapshotKey, val attempts: Int) :
    CoSecException("Snapshot document [$key] kept changing: gave up after $attempts attempts.")
