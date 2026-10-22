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

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.codec.ByteArrayCodec
import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.policy.CachePolicy
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.cache.AppSpacedRoleId
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.CoSecCacheSpecs.storeOfRecord
import me.ahoo.cosec.cache.CoSecTestCaches
import me.ahoo.cosec.cache.GlobalPolicyIndexCache
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.snapshot.SnapshotKey
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The real Redis deployments the snapshot tests run against (AGENTS.md: real Redis, never mocks).
 *
 * [REDIS] (`localhost:6379`) always runs, as CI provides it. [VALKEY] and [CLUSTER] are opt-in; when their variable is
 * unset, every case using them is reported as skipped, never passed:
 *
 * - `COSEC_TEST_VALKEY_PORT=6380`: a Valkey on that port of localhost;
 * - `COSEC_TEST_REDIS_CLUSTER_NODES=127.0.0.1:7000,127.0.0.1:7001,...`: the seed nodes of a Redis Cluster.
 *
 * Environment variables or system properties of the same name both work. To run all three locally:
 *
 * ```
 * COSEC_TEST_VALKEY_PORT=6380 \
 * COSEC_TEST_REDIS_CLUSTER_NODES=127.0.0.1:7000,127.0.0.1:7001,127.0.0.1:7002,127.0.0.1:7003,127.0.0.1:7004,127.0.0.1:7005 \
 * ./gradlew :cosec-cocache:check
 * ```
 */
enum class RedisTarget(private val variable: String?) {
    REDIS(null),
    VALKEY(VALKEY_PORT),
    CLUSTER(CLUSTER_NODES),
    ;

    /** The URIs of this target, or null when its variable is unset. */
    private fun urisOrNull(): List<String>? = when (this) {
        REDIS -> listOf("redis://localhost:6379")
        VALKEY -> setting(VALKEY_PORT)?.let { listOf("redis://localhost:$it") }
        CLUSTER -> setting(CLUSTER_NODES)?.split(',')?.map { "redis://${it.trim()}" }?.filter { it != "redis://" }
    }

    /** This target's URIs; skips the running test when the target is not configured. */
    private fun uris(): List<String> {
        val uris = urisOrNull()
        assumeTrue(!uris.isNullOrEmpty()) { "$name is opt-in: set $variable to run this case." }
        return checkNotNull(uris)
    }

    fun backend(): RedisBackend {
        val redisUris = uris().map { RedisURI.create(it) }
        return if (this == CLUSTER) RedisBackend.cluster(redisUris) else RedisBackend.standalone(redisUris.single())
    }

    /** Writes [value] under [key] with a plain SET, as content CoCache did not write (format 0). */
    fun rawSet(key: String, value: ByteArray) {
        val redisUris = uris().map { RedisURI.create(it) }
        if (this == CLUSTER) {
            val client = RedisClusterClient.create(redisUris)
            try {
                client.connect(ByteArrayCodec.INSTANCE).use { it.sync().set(key.encodeToByteArray(), value) }
            } finally {
                client.shutdown()
            }
        } else {
            val client = RedisClient.create(redisUris.single())
            try {
                client.connect(ByteArrayCodec.INSTANCE).use { it.sync().set(key.encodeToByteArray(), value) }
            } finally {
                client.shutdown()
            }
        }
    }
}

private const val VALKEY_PORT = "COSEC_TEST_VALKEY_PORT"
private const val CLUSTER_NODES = "COSEC_TEST_REDIS_CLUSTER_NODES"

private fun setting(name: String): String? =
    (System.getenv(name) ?: System.getProperty(name))?.takeIf { it.isNotBlank() }

/** One CoSec instance's snapshot side: its own runtime (local copies) over a [backend] other instances may share. */
class SnapshotNode(
    backend: CoherenceBackend,
    val keyPrefix: String,
    localMaxAge: Duration = CoSecCacheSpecs.DEFAULT_SNAPSHOT_LOCAL_MAX_AGE,
) : AutoCloseable {
    val runtime: CacheRuntime = CacheRuntime.builder().backend(backend).build()
    val cache: PolicySnapshotCache =
        runtime.createPolicySnapshotCache(CoSecCacheSpecs.policySnapshot(keyPrefix, localMaxAge = localMaxAge))
    val store = RedisSnapshotDocumentStore(cache)
    val publisher = RedisSnapshotPublisher(cache, store)

    override fun close() {
        runtime.close()
    }
}

/** Writes any bytes as a snapshot document's value, as a buggy or foreign writer would; its own runtime. */
class RawSnapshotWriter(backend: CoherenceBackend, keyPrefix: String) : AutoCloseable {
    private val runtime = CacheRuntime.builder().backend(backend).build()
    val cache: Cache<SnapshotKey, ByteArray> = runtime.create(
        CacheSpec.builder<SnapshotKey, ByteArray>("RawSnapshot")
            .namespace(CoSecCacheSpecs.cacheNamespace(keyPrefix, CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME))
            .keyPrefix("")
            .keyEncoder(SnapshotKeyEncoder)
            .codec(RawCodec)
            .policy(CachePolicy.DEFAULT.storeOfRecord())
            .build()
    )

    fun put(key: SnapshotKey, value: String) = cache.put(key, value.encodeToByteArray())

    override fun close() {
        runtime.close()
    }
}

/** Stores bytes as they are. */
object RawCodec : Codec<ByteArray> {
    override fun encode(value: ByteArray): ByteArray = value

    override fun decode(bytes: ByteArray): ByteArray = bytes
}

/**
 * A backend whose invalidations never arrive: subscriptions are accepted and never called. Closing it leaves the
 * wrapped backend open.
 */
class SilentChannelBackend(private val delegate: CoherenceBackend) : CoherenceBackend by delegate {
    override val channel: InvalidationChannel = object : InvalidationChannel by delegate.channel {
        override fun subscribe(namespace: String, listener: InvalidationListener): Subscription = Subscription {}
    }

    override fun close() = Unit
}

/**
 * A backend that calls the listeners of a namespace in the reverse order of their subscriptions, so a listener added
 * after a cache was created hears of a change before that cache's engine drops its local copy (RFC 0003 §6 F9).
 * Closing it ends its subscriptions and leaves the wrapped backend open.
 */
class ReversingChannelBackend(private val delegate: CoherenceBackend) : CoherenceBackend by delegate {
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<InvalidationListener>>()
    private val subscriptions = ConcurrentHashMap<String, Subscription>()

    override val channel: InvalidationChannel = object : InvalidationChannel by delegate.channel {
        override fun subscribe(namespace: String, listener: InvalidationListener): Subscription {
            val namespaceListeners = listeners.computeIfAbsent(namespace) { CopyOnWriteArrayList() }
            namespaceListeners.add(listener)
            subscriptions.computeIfAbsent(namespace) {
                delegate.channel.subscribe(
                    namespace,
                    object : InvalidationListener {
                        override fun onInvalidated(keys: List<String>, origin: NodeId) {
                            namespaceListeners.reversed().forEach { it.onInvalidated(keys, origin) }
                        }

                        override fun onReset() {
                            namespaceListeners.reversed().forEach { it.onReset() }
                        }
                    }
                )
            }
            return Subscription { namespaceListeners.remove(listener) }
        }
    }

    override fun close() {
        subscriptions.values.forEach { it.close() }
    }
}

/** Polls [condition] until it holds or [timeout] passes. */
fun awaitTrue(timeout: Duration = Duration.ofSeconds(10), what: String = "condition", condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Timed out waiting for $what." }
        Thread.sleep(POLL_MILLIS)
    }
}

private const val POLL_MILLIS = 20L

fun newKeyPrefix(): String = CoSecTestCaches.newKeyPrefix()

/** An app permission with one permission, `p1`, that allows everything. */
fun appPermission(appId: String, vararg permissionIds: String = arrayOf("p1")): AppPermissionData = AppPermissionData(
    id = appId,
    groups = listOf(
        PermissionGroupData(
            "g",
            permissions = permissionIds.map { PermissionData(id = it, name = it, action = AllActionMatcher.INSTANCE) },
        )
    ),
)

/**
 * Writes 7.x policy data as stored JSON, whether or not this instance could decode it, in 7.0's layout; its own
 * runtime. The global policy index is written through 7.0's cache.
 */
class Raw7xWriter(backend: CoherenceBackend, private val keyPrefix: String) : AutoCloseable {
    private val runtime = CacheRuntime.builder().backend(backend).build()
    private val caches = CoSecTestCaches(backend, keyPrefix)

    private fun raw(cacheName: String): Cache<String, ByteArray> = runtime.create(
        CacheSpec.builder<String, ByteArray>("Raw$cacheName")
            .namespace(CoSecCacheSpecs.cacheNamespace(keyPrefix, cacheName))
            .keyPrefix("")
            .keyEncoder { it }
            .codec(RawCodec)
            .policy(CachePolicy.DEFAULT.storeOfRecord())
            .build()
    )

    private val policies = raw(CoSecCacheSpecs.POLICY_CACHE_NAME)
    private val appPermissions = raw(CoSecCacheSpecs.APP_PERMISSION_CACHE_NAME)
    private val grants = raw(CoSecCacheSpecs.ROLE_PERMISSION_CACHE_NAME)

    fun policy(id: String, json: String) = policies.put(id, json.encodeToByteArray())

    fun index(vararg ids: String) {
        caches.globalPolicyIndexCache.add(GlobalPolicyIndexCache.CACHE_KEY, ids.toList())
    }

    fun appPermission(appId: String, json: String) = appPermissions.put(appId, json.encodeToByteArray())

    fun grants(id: AppSpacedRoleId, json: String) = grants.put(id.toString(), json.encodeToByteArray())

    override fun close() {
        caches.close()
        runtime.close()
    }
}
