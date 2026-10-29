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

package me.ahoo.cosec.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.membership.MembershipProvider

/**
 * Where the application's memberships come from: CoSec does not say where they are stored (RFC 0006 §5.5.2).
 *
 * Like every SPI of the pipeline, it must not block the calling thread: an implementation over a blocking client (JDBC,
 * say) moves the call to its own bounded executor (RFC 0006 §7.7). Answer null for a principal you do not know, which
 * refuses its credentials, and [Memberships.active] false for one that is deactivated (RFC 0009 §4.2); report a failed
 * lookup by throwing.
 */
fun interface MembershipLoader {
    suspend fun load(tenant: TenantId, principal: PrincipalId): Memberships?
}

/**
 * A [MembershipProvider] that caches what [loader] answers in [cache], shared by every instance through Redis
 * (RFC 0006 §5.5.2):
 *
 * - a hit, local or in Redis, answers without asking [loader]; a local hit answers on the caller's thread without
 *   suspending, and a Redis read does not block (CoCache's async view);
 * - a miss asks [loader] through the cache's read-through fill, which stores its answer locally and in Redis, where it
 *   expires after the cache's TTL ([CoSecCacheSpecs.membership]): the TTL bounds how long a change takes to apply when
 *   its invalidation is lost. A fill, unlike a `put`, is not a write: the next request on this instance is a local hit,
 *   where a `put` would leave it to read Redis once more. An unknown principal (null) is not cached: each request for
 *   it asks [loader] again;
 * - a failed cache read throws, as does [loader], so the pipeline fails closed with 503 and never treats the
 *   principal as having no memberships (`MembershipCacheFailureTest`). A fill Redis cannot store still answers what
 *   [loader] said, kept on this instance only: the answer is the loader's own, never a guess.
 *
 * Call [invalidate] when a principal's memberships change. An invalidation that arrives while another request is
 * loading the old memberships can be overwritten by them; the TTL still bounds that.
 */
class CoCacheMembershipProvider(
    private val cache: MembershipCache,
    private val loader: MembershipLoader,
) : MembershipProvider {
    override suspend fun memberships(tenant: TenantId, principal: PrincipalId): Memberships? {
        val key = MembershipCache.key(tenant, principal)
        // The load runs in this request's context but not as its child: a fill other requests wait for is not cancelled
        // when this one gives up. Started undispatched, a loader that answers at once fills on the caller's thread.
        val context = currentCoroutineContext().minusKey(Job)
        return try {
            cache.async().get(key) { _ ->
                CoroutineScope(context).future(start = CoroutineStart.UNDISPATCHED) { load(tenant, principal) }
            }.await()
        } catch (_: UnknownPrincipal) {
            null
        }
    }

    /** What [loader] answers; an unknown principal fails the fill, so that it is not cached as missing. */
    private suspend fun load(tenant: TenantId, principal: PrincipalId): Memberships {
        val loaded = loader.load(tenant, principal) ?: throw UnknownPrincipal
        check(loaded.source == Memberships.Source.PROVIDER) {
            "The membership loader answered memberships from [${loaded.source}] for principal [${principal.value}]."
        }
        return loaded
    }

    /** The loader does not know the principal: never cached, so a principal added later is known at once. */
    private object UnknownPrincipal : RuntimeException() {
        private fun readResolve(): Any = UnknownPrincipal

        override fun fillInStackTrace(): Throwable = this
    }

    /** Drops the cached memberships of [principal] in [tenant] on every instance; the next request loads them. */
    fun invalidate(tenant: TenantId, principal: PrincipalId) {
        cache.invalidate(MembershipCache.key(tenant, principal))
    }
}
