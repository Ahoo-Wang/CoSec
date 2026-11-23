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

import me.ahoo.cocache.api.spi.InvalidationChannel
import me.ahoo.cocache.api.spi.InvalidationFrame
import me.ahoo.cocache.api.spi.InvalidationListener
import me.ahoo.cocache.api.spi.Keyspace
import me.ahoo.cocache.api.spi.NodeId
import me.ahoo.cocache.api.spi.Subscription
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * How the instances tell each other that a principal's memberships changed (DR1-D-2): a frame on CoCache's invalidation
 * channel of the memberships' keyspace, `<s>:inv:membership`, carrying only the keys. Nothing is stored in Redis: a
 * frame anyone with publish permission on the channel sends can only drop local entries, which load again from the
 * application's loader, never answer for it.
 */
@InternalCoSecApi
class MembershipInvalidations(
    private val channel: InvalidationChannel,
    private val keyspace: Keyspace,
    private val node: NodeId = NodeId.random(),
) {
    /** Drops what [local] holds of every key a frame names, and everything on a reset; until the result is closed. */
    fun subscribe(local: LocalMemberships): Subscription = channel.subscribe(
        keyspace,
        object : InvalidationListener {
            override fun onInvalidated(keys: List<String>, origin: NodeId) {
                keys.forEach(local::invalidate)
            }

            override fun onReset() = local.clear()
        },
    )

    /** Tells every instance that [key]'s memberships changed: a frame names the storage key, as CoCache's do. */
    suspend fun publish(key: String) {
        channel.publish(keyspace, InvalidationFrame.encode(node, listOf(keyspace.storageKey(key))))
    }

    @InternalCoSecApi
    companion object {
        /**
         * The keyspace of the memberships under [keyPrefix], `{keyPrefix}:` and `membership`: its channel,
         * `{keyPrefix}:inv:membership`, is the one the shared cache of the 8.0 previews used, so their frames apply.
         */
        fun keyspace(keyPrefix: String): Keyspace {
            val path = CoSecCacheSpecs.membershipPath(keyPrefix)
            val split = path.lastIndexOf(':')
            return Keyspace(path.substring(0, split + 1), path.substring(split + 1))
        }
    }
}

/** The memberships' invalidations of the deployment under [keyPrefix], on this factory's Redis channel. */
@InternalCoSecApi
fun CoCacheFactory.membershipInvalidations(keyPrefix: String): MembershipInvalidations =
    MembershipInvalidations(runtime.backend.channel, MembershipInvalidations.keyspace(keyPrefix))
