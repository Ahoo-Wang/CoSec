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

package me.ahoo.cosec.cocache

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.membership.MembershipAnswer
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spi.membership.MembershipUnavailableException
import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * A [MembershipProvider] that caches what [loader] answers in this process only ([LocalMemberships], RFC 0006 §5.5.2,
 * DR1-D-2). The name is from when the cache was CoCache's, shared through Redis; it is kept, since renaming a public
 * class breaks the applications that name it. Nothing it answers is read from Redis: a principal's memberships are the
 * application's loader's answer on this instance, never what another process, or anyone holding a Redis credential,
 * wrote.
 *
 * - a fresh entry answers on the caller's thread, without suspending or any I/O;
 * - a miss asks [loader], once for all the requests of this instance that miss the same principal; its answer is kept
 *   [LocalMemberships]' lifetime, the longest a membership change takes to apply when its invalidation is lost. An
 *   unknown principal (null) is not kept with the memberships: it is remembered on this instance alone, for
 *   [unknownLife] (R2C-18), so that a token of a removed member does not reach [loader] on every request. A failed
 *   load or a timeout is never kept, so an outage never reads as "no such principal" or "no roles": the pipeline fails
 *   closed with 503 (`MEM-2`);
 * - [invalidate] drops the principal's entry here and tells every instance to drop theirs ([invalidations], CoCache's
 *   invalidation channel); a frame that is lost, or never sent, is bounded by the lifetime. A frame anyone sends can
 *   only make instances load again.
 *
 * Each instance asks [loader] for the principals it serves: N instances make up to N loads of one principal per
 * lifetime, where the shared cache made one.
 */
class CoCacheMembershipProvider @InternalCoSecApi constructor(
    private val cache: LocalMemberships,
    /**
     * How one fill may take. Requests on this instance that miss the same key wait for the same fill, which no single
     * request owns: without its own bound, a loader that never answers would make that principal unavailable here
     * until a restart. When it passes, the fill fails, and the next request starts a new one. No longer than the
     * membership's share of `cosec.pipeline.timeout`, which each request still waits at most.
     */
    private val fillTimeout: Duration = DEFAULT_FILL_TIMEOUT,
    /**
     * How long this instance remembers that [loader] did not know a principal, so that its requests do not reach the
     * loader each time ([DEFAULT_UNKNOWN_LIFE] in the starter). Zero remembers nothing. At most
     * [LocalMemberships.DEFAULT_TTL], the longest a positive entry lives by default.
     */
    private val unknownLife: Duration = DEFAULT_UNKNOWN_LIFE,
    /** How the instances tell each other of a change; none in a single process, or a test. */
    private val invalidations: MembershipInvalidations? = null,
    private val loader: MembershipLoader,
) : MembershipProvider {
    init {
        require(
            !fillTimeout.isNegative && !fillTimeout.isZero
        ) { "The fill timeout must be positive, not $fillTimeout." }
        require(!unknownLife.isNegative && unknownLife <= LocalMemberships.DEFAULT_TTL) {
            "The unknown-principal life is between zero and ${LocalMemberships.DEFAULT_TTL}, not $unknownLife."
        }
    }

    private val unknown = UnknownPrincipals(unknownLife)

    private val subscription: Subscription? = invalidations?.subscribe(cache)

    // Whatever the load's failure is wrapped in, an unknown principal is found by its sentinel; anything else rethrows.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun memberships(tenant: TenantId, principal: PrincipalId): MembershipAnswer {
        val key = key(tenant, principal)
        if (unknown.contains(key)) return MembershipAnswer.Unknown
        cache.peek(key)?.let { return MembershipAnswer.Known(it) }
        // The load runs in this request's context but not as its child: a fill other requests wait for is not cancelled
        // when this one gives up. Started undispatched, a loader that answers at once fills on the caller's thread.
        val context = currentCoroutineContext().minusKey(Job)
        val memberships = try {
            cache.get(key) {
                CoroutineScope(context).future(start = CoroutineStart.UNDISPATCHED) { load(tenant, principal) }
            }.await()
        } catch (e: Exception) {
            if (e.causes().any { it === UnknownPrincipal }) {
                unknown.remember(key)
                return MembershipAnswer.Unknown
            }
            throw e
        }
        return MembershipAnswer.Known(memberships)
    }

    /** What [loader] answers within [fillTimeout]; an unknown principal fails the fill, so it is not cached as missing. */
    private suspend fun load(tenant: TenantId, principal: PrincipalId): Memberships {
        val answer = withTimeoutOrNull(fillTimeout.toKotlinDuration()) { loader.load(tenant, principal) }
            ?: throw MembershipUnavailableException(
                "The membership loader did not answer within $fillTimeout for principal [${principal.value}]."
            )
        val loaded = when (answer) {
            MembershipAnswer.Unknown -> throw UnknownPrincipal
            is MembershipAnswer.Known -> answer.memberships
        }
        check(loaded.source == Memberships.Source.PROVIDER) {
            "The membership loader answered memberships from [${loaded.source}] for principal [${principal.value}]."
        }
        return loaded
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(
        this
    ) { it.cause.takeIf { cause -> cause !== it } }

    /**
     * The loader does not know the principal: not cached with the memberships (see [unknownLife]). One shared
     * instance, so it records neither a stack trace nor suppressed exceptions.
     */
    private object UnknownPrincipal : RuntimeException(null, null, false, false) {
        private fun readResolve(): Any = UnknownPrincipal
    }

    /**
     * Drops the cached memberships of [principal] in [tenant] here at once, and on every other instance once the frame
     * reaches it; the next request loads them. Sending the frame does not block: it is published in the background, and
     * a frame that cannot be sent leaves the other instances to the lifetime.
     */
    fun invalidate(tenant: TenantId, principal: PrincipalId) {
        val key = key(tenant, principal)
        unknown.forget(key)
        cache.invalidate(key)
        val channel = invalidations ?: return
        publishing.launch { channel.publish(key) }
    }

    private val publishing =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
                log.warn(error) { "Telling the other instances of a membership change failed; their entries expire meanwhile." }
            }
        )

    /** Stops listening for the other instances' changes: the starter's bean calls it as the context closes. */
    @InternalCoSecApi
    fun close() {
        subscription?.close()
        publishing.cancel()
    }

    companion object {
        private val log = KotlinLogging.logger {}

        /** The membership's share of the default `cosec.pipeline.timeout`, which a request waits at most. */
        val DEFAULT_FILL_TIMEOUT: Duration = Duration.ofMillis(500)

        /**
         * How long the starter's provider remembers a principal the loader did not know: a constant, as no state of the
         * process says how soon a removed member's token is used again, and the loader answers the next ask anyway.
         */
        val DEFAULT_UNKNOWN_LIFE: Duration = Duration.ofSeconds(5)

        /** The key of [principal] in [tenant]: the same unambiguous subject key as [RevokedSubjectCache.key]. */
        internal fun key(tenant: TenantId, principal: PrincipalId): String = RevokedSubjectCache.key(tenant, principal)
    }
}
