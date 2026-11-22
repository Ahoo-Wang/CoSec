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
import me.ahoo.cocache.core.SetStoreSpec
import me.ahoo.cocache.core.Spec
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.policy.PolicyField
import me.ahoo.cocache.core.policy.ReadFailureMode
import me.ahoo.cocache.core.policy.Ttl
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs.DEFAULT_MEMBERSHIP_TTL
import me.ahoo.cosec.cocache.CoSecCacheSpecs.INSTANCE_RESIDENT_CACHE_NAME
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

    /** The shadow caches whose life is a bound something relies on (`CTL-73`, `CTL-75`): exactly this, no jitter. */
    private val EXACT_LIVES: Map<String, Duration> = mapOf(
        INSTANCE_STATUS_CACHE_NAME to ShadowCacheSpecs.STATUS_TTL,
        INSTANCE_RESIDENT_CACHE_NAME to ShadowCacheSpecs.RESIDENT_TTL,
    )

    /** How long a revocation table keeps "not revoked" locally ([RevokedTokenCache.MISSING_TTL_SECONDS]). */
    private val NOT_REVOKED_FOR: Duration = Duration.ofSeconds(RevokedTokenCache.MISSING_TTL_SECONDS)

    fun of(cacheName: String, readOnly: Boolean = false): Locks = Locks.builder(LOCKED_BY).apply {
        lockLayout(*LayoutField.entries.toTypedArray())
        // Read-only is CoSec's (SNP-18): where the control plane runs it writes the documents, and elsewhere a
        // read-only user must be enough; a property or customizer that changes it either way is refused here.
        verify { readOnlyAsDeclared(it, readOnly) }
        when (cacheName) {
            in SetsOfRecord.CACHE_NAMES -> setOfRecord()

            in STORE_OF_RECORD_CACHE_NAMES -> {
                verify { require(it is StoreSpec<*, *>) { "CoSec's ${it.name} is the store of record: a Store." } }
                lock(PolicyField.TTL, PolicyField.UNKNOWN_READ)
                constrain(PolicyField.TTL) { Ttl.Forever }
                constrain(PolicyField.UNKNOWN_READ) { UnknownReadMode.THROW }
                if (cacheName == POLICY_SNAPSHOT_CACHE_NAME) {
                    // One local max age, 3 × cosec.authorization.snapshot.revalidate-interval (RFC 0008 §4.4).
                    lock(PolicyField.LOCAL_MAX_AGE)
                }
            }

            in SHADOW_CACHE_NAMES -> {
                verify { require(it is StoreSpec<*, *>) { "CoSec's ${it.name} is a Store." } }
                lock(PolicyField.UNKNOWN_READ, PolicyField.TTL)
                constrain(PolicyField.UNKNOWN_READ) { UnknownReadMode.THROW }
                // The reports outlive any window, and a status counts for a minute (RFC 0007 §6.5): fixed, as on main.
                val exact = EXACT_LIVES[cacheName]
                constrain(PolicyField.TTL) { Ttl.of(exact ?: ShadowCacheSpecs.REPORT_TTL) }
                if (exact != null) {
                    // A status's life is a liveness bound (CTL-73): an undecodable one counts until its key expires,
                    // so the key lives exactly STATUS_TTL. CoCache's default jitter would make it 1 to 3 minutes. A
                    // resident record is rewritten at half its life (CTL-75), which a jittered life could undercut.
                    lock(PolicyField.TTL_JITTER)
                    constrain(PolicyField.TTL_JITTER) { Duration.ZERO }
                }
            }

            REVOKED_TOKEN_CACHE_NAME, REVOKED_SUBJECT_CACHE_NAME -> {
                verify { require(it is StoreSpec<*, *>) { "CoSec's ${it.name} is a Store." } }
                // A failed read is unknown, never absent: absent would read "not revoked" to refresh too (H1).
                lock(PolicyField.UNKNOWN_READ, PolicyField.READ_TIMEOUT)
                constrain(PolicyField.UNKNOWN_READ) { UnknownReadMode.THROW }
                // A read that times out is unavailable, which the pipeline lets through (PIP-7): a timeout shorter than
                // the store's command timeout, from cocache.defaults.store.read-timeout say, would widen that.
                constrain(PolicyField.READ_TIMEOUT) { COMMAND_TIMEOUT }
                // "Not revoked" is kept locally for at most MISSING_TTL_SECONDS: past a lost invalidation, a longer
                // absent-ttl would accept a revoked token for that long. Of the other fields of a Store, none can lengthen
                // it: a missing entry lives min(absent-ttl, local-max-age); a revocation is written with a lifetime of its
                // own, which neither ttl nor ttl-jitter shortens in Redis; local-maximum-size only evicts.
                lock(PolicyField.ABSENT_TTL)
                constrain(PolicyField.ABSENT_TTL) { NOT_REVOKED_FOR }
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

    /**
     * A set of record: never expiring, and with no local copy a read could answer from, degraded or not (`CTL-42`;
     * [SetsOfRecord.LOCAL_MAX_AGE] says why 1 ns does that). A SetStore has no unknown-read: CoCache's `SetEngine` always throws
     * on a read it cannot answer.
     */
    private fun Locks.Builder.setOfRecord() {
        verify { require(it is SetStoreSpec<*, *>) { "CoSec's ${it.name} is the store of record: a SetStore." } }
        lock(PolicyField.TTL, PolicyField.LOCAL_MAX_AGE, PolicyField.DEGRADED_LOCAL_MAX_AGE)
        constrain(PolicyField.TTL) { Ttl.Forever }
        constrain(PolicyField.LOCAL_MAX_AGE) { SetsOfRecord.LOCAL_MAX_AGE }
        constrain(PolicyField.DEGRADED_LOCAL_MAX_AGE) { SetsOfRecord.LOCAL_MAX_AGE }
    }

    private fun readOnlyAsDeclared(spec: Spec<*, *>, declared: Boolean) {
        val effective = when (spec) {
            is StoreSpec<*, *> -> spec.readOnly
            is SetStoreSpec<*, *> -> spec.readOnly
            else -> false
        }
        check(effective == declared) {
            if (declared) {
                "CoSec reads ${spec.name} read-only in this process, which does not run the control plane: a " +
                    "cocache.* property or customizer cannot make it read-write (SNP-18)."
            } else {
                "CoSec writes ${spec.name} in this process: a cocache.* property or customizer cannot make it " +
                    "read-only (SNP-18)."
            }
        }
    }
}
