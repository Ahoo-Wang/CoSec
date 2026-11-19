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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.runtime.snapshot.DocumentState
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * UB-3: an instance whose platform document is absent refuses traffic (readiness, 503), and nothing else says why. This
 * says it: a WARN as soon as the first read answers "absent", then again every [interval] while it lasts, and one INFO
 * when the document is installed. It names where the instance looked ([where]: the key, the key prefix, the Redis it
 * reads, never a credential) and what to check. Reads [SnapshotSource.peek] only: no I/O, no state beyond the timer.
 */
internal class PlatformDocumentWatch(
    private val source: SnapshotSource<*, *>,
    /** What the line says about the lookup: the key, the prefix and the Redis. No secret. */
    private val where: String,
    private val keyPrefix: String,
    private val interval: Duration,
    private val warn: (String) -> Unit = { line -> log.warn { line } },
    private val info: (String) -> Unit = { line -> log.info { line } },
    poll: Duration = Duration.ofSeconds(1),
) : AutoCloseable {
    private var lastWarn = Long.MIN_VALUE
    private var warned = false

    private val timer = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "cosec-platform-document-watch").apply { isDaemon = true }
    }

    init {
        timer.scheduleWithFixedDelay(::check, 0, poll.toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun check() {
        try {
            when (source.peek()?.policies) {
                DocumentState.Absent -> absent()
                is DocumentState.Present<*> -> {
                    if (warned) {
                        info("The platform document is installed: this instance is ready to decide.")
                    }
                    timer.shutdown()
                }

                else -> Unit
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // A watch that cannot read says nothing: it must not take the timer down with it.
            log.debug(e) { "The platform document watch could not read the snapshot source." }
        }
    }

    private fun absent() {
        val now = System.nanoTime()
        if (warned && now - lastWarn < interval.toNanos()) {
            return
        }
        warned = true
        lastWarn = now
        warn(
            "No platform document under $where: this instance is not ready, refuses traffic and answers 503 " +
                "until one is published. Check, in this order: a first deployment that has published nothing yet " +
                "(\"cosec-migrate publish --key-prefix $keyPrefix\"), a wrong cosec.key-prefix (this instance reads " +
                "[$keyPrefix]), a wrong Redis database or host. Said again every $interval while it lasts."
        )
    }

    override fun close() {
        timer.shutdownNow()
    }

    companion object {
        private val log = KotlinLogging.logger {}

        /** A watch of the platform document of the snapshot under [keyPrefix], in the Redis behind [factory]. */
        fun of(
            source: SnapshotSource<*, *>,
            keyPrefix: String,
            interval: Duration,
            factory: Any?,
        ): PlatformDocumentWatch {
            val key = CoSecCacheSpecs.storagePath(keyPrefix, CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME) +
                ":" + SnapshotKeyEncoder.PLATFORM
            return PlatformDocumentWatch(
                source,
                "[$key] in Redis ${RedisLocation.describe(factory)}",
                keyPrefix,
                interval
            )
        }
    }
}
