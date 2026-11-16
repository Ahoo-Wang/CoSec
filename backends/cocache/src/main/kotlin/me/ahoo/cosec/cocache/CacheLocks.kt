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

import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.LayoutField
import me.ahoo.cocache.core.Locks
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.policy.PolicyField
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.core.policy.Ttl
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs.DEFAULT_MEMBERSHIP_TTL
import me.ahoo.cosec.cocache.CoSecCacheSpecs.INSTANCE_STATUS_CACHE_NAME
import me.ahoo.cosec.cocache.CoSecCacheSpecs.LOCKED_BY
import me.ahoo.cosec.cocache.CoSecCacheSpecs.MEMBERSHIP_CACHE_NAME
import me.ahoo.cosec.cocache.CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME
import me.ahoo.cosec.cocache.CoSecCacheSpecs.REVOKED_SUBJECT_CACHE_NAME
import me.ahoo.cosec.cocache.CoSecCacheSpecs.REVOKED_TOKEN_CACHE_NAME
import me.ahoo.cosec.cocache.CoSecCacheSpecs.SHADOW_CACHE_NAMES
import me.ahoo.cosec.cocache.CoSecCacheSpecs.STORE_OF_RECORD_CACHE_NAMES
import me.ahoo.cosec.cocache.CoSecCacheSpecs.TOKEN_FAMILY_CACHE_NAME
import me.ahoo.cosec.cocache.shadow.ShadowCacheSpecs
import java.time.Duration
import java.time.temporal.ChronoUnit

/** The locks and constraints of CoSec's caches ([CoSecCacheSpecs.locks]), kept out of the specs' object. */
internal object CacheLocks {
    /**
     * A read timeout CoCache keeps as the store's command timeout: it stores any value of 36,500 days or more as none
     * (`PolicyField.READ_TIMEOUT`).
     */
    private val COMMAND_TIMEOUT: Duration = ChronoUnit.FOREVER.duration

    fun of(cacheName: String): Locks = Locks.builder(LOCKED_BY).apply {
        lockLayout(*LayoutField.entries.toTypedArray())
        when (cacheName) {
            in STORE_OF_RECORD_CACHE_NAMES -> {
                verify { require(it is StoreSpec<*, *>) { "CoSec's ${it.name} is the store of record: a Store." } }
                lock(PolicyField.TTL, PolicyField.UNKNOWN_READ)
                constrain(PolicyField.TTL) { Ttl.Forever }
                constrain(PolicyField.UNKNOWN_READ) { UnknownReadMode.THROW }
                if (cacheName == POLICY_SNAPSHOT_CACHE_NAME) {
                    // One local max age, cosec.authorization.snapshot.local-max-age (RFC 0008 §4.4).
                    lock(PolicyField.LOCAL_MAX_AGE)
                }
            }

            in SHADOW_CACHE_NAMES -> {
                verify { require(it is StoreSpec<*, *>) { "CoSec's ${it.name} is a Store." } }
                lock(PolicyField.UNKNOWN_READ, PolicyField.TTL)
                constrain(PolicyField.UNKNOWN_READ) { UnknownReadMode.THROW }
                // The reports outlive any window, and a status counts for a minute (RFC 0007 §6.5): fixed, as on main.
                val ttl = if (cacheName == INSTANCE_STATUS_CACHE_NAME) ShadowCacheSpecs.STATUS_TTL else ShadowCacheSpecs.REPORT_TTL
                constrain(PolicyField.TTL) { Ttl.of(ttl) }
            }

            REVOKED_TOKEN_CACHE_NAME, REVOKED_SUBJECT_CACHE_NAME -> {
                verify { require(it is StoreSpec<*, *>) { "CoSec's ${it.name} is a Store." } }
                // A failed read is unknown, never absent: absent would read "not revoked" to refresh too (H1).
                lock(PolicyField.UNKNOWN_READ, PolicyField.READ_TIMEOUT)
                constrain(PolicyField.UNKNOWN_READ) { UnknownReadMode.THROW }
                // A read that times out is unavailable, which the pipeline lets through (PIP-7): a timeout shorter than
                // the store's command timeout, from cocache.defaults.store.read-timeout say, would widen that.
                constrain(PolicyField.READ_TIMEOUT) { COMMAND_TIMEOUT }
            }

            TOKEN_FAMILY_CACHE_NAME -> {
                verify { require(it is StoreSpec<*, *>) { "CoSec's ${it.name} is a Store." } }
                // A family that cannot be read is no answer, never "no family" (RFC 0009): refresh and logout fail.
                lock(PolicyField.UNKNOWN_READ)
                constrain(PolicyField.UNKNOWN_READ) { UnknownReadMode.THROW }
            }

            MEMBERSHIP_CACHE_NAME -> {
                verify {
                    require(it is CacheSpec<*, *> && it.externallySourced) {
                        "CoSec's ${it.name} is an externally sourced Cache."
                    }
                }
                lock(PolicyField.READ_FAILURE, PolicyField.UNKNOWN_READ)
                constrain(PolicyField.TTL) { it.finiteOr(DEFAULT_MEMBERSHIP_TTL) }
                constrain(PolicyField.TTL_JITTER) { Duration.ZERO }
                constrain(PolicyField.READ_FAILURE) { ReadFailureMode.THROW }
                constrain(PolicyField.UNKNOWN_READ) { UnknownReadMode.THROW }
            }

            else -> throw IllegalArgumentException("[$cacheName] is not one of CoSec's caches.")
        }
    }.build()
}
