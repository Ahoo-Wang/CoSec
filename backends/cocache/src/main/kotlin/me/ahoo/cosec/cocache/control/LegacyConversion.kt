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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.cocache.control

import me.ahoo.cocache.api.Store
import me.ahoo.cocache.core.CacheAdmin
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.KeyInspection
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.control.ConditionalStores.read
import me.ahoo.cosec.control.store.ReferrerEntry
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKeyText

/**
 * Converts a back-reference value of the format before 8.0.0-beta.5 into a set, for `cosec-migrate repair-references`
 * (`MIG-4`). Only a value CoCache inspects as a value, not a set, is converted: a set, whoever wrote it and whatever it
 * holds, is never replaced, so a member a publish added is never lost. No other writer can change an old-format value
 * meanwhile (CoCache refuses to add to or remove from it), and the repair holds its lease against a second run.
 *
 * A converted entry says the referrer refers to the policy, never that it defines the id itself, and keeps the revision
 * that indexed it (at least 1): the conservative direction for a removal (`CTL-43`). The repair then adds what each head
 * defines and prunes what no head has.
 */
@InternalCoSecApi
class LegacyConversion internal constructor(
    private val store: CoCacheControlStore,
    private val references: CacheAdmin,
    private val legacy: Store<Pair<SnapshotKey, PolicyId>, Map<SnapshotKey, Long>>,
    private val legacyAdmin: CacheAdmin,
) {
    /**
     * Whether the value of [target]'s [policy] is in the format before 8.0.0-beta.5. A value CoCache can say nothing of,
     * such as one CoCache 6 wrote, throws: it is neither converted nor taken for a set.
     */
    fun isLegacy(target: SnapshotKey, policy: PolicyId): Boolean {
        val encoded = ReferenceKeyEncoder.encode(ReferenceKey(target, policy))
        // A value only the old format's cache reads as one; a set only the sets' cache reads as one.
        if (legacyAdmin.inspect(encoded).remote?.state == KeyInspection.RemoteState.VALUE) {
            return true
        }
        val remote = references.inspect(encoded).remote
        return when (remote?.state) {
            KeyInspection.RemoteState.SET,
            KeyInspection.RemoteState.ABSENT,
            KeyInspection.RemoteState.MISS,
            null -> false
            else -> throw IllegalStateException(
                "The back-reference value of [$target] policy [${SnapshotKeyText.printable(policy.value)}] cannot be " +
                    "converted: CoCache reads it as ${remote.state} (${remote.error}).",
            )
        }
    }

    /** Converts the value of [target]'s [policy] if it is in the format before 8.0.0-beta.5; whether it did. */
    suspend fun convert(target: SnapshotKey, policy: PolicyId): Boolean {
        if (!isLegacy(target, policy)) {
            return false
        }
        val what = "The back-reference value of [$target] policy [${SnapshotKeyText.printable(policy.value)}]"
        val old = read(legacy, target to policy, what)?.first ?: return false
        val entries = old.mapTo(LinkedHashSet()) { (referrer, revision) -> ReferrerEntry(referrer, maxOf(1, revision)) }
        store.convertReferrers(target, policy, entries)
        return true
    }

    companion object {
        /**
         * The conversion over [store], which [factory] created, reading the old format through [legacyFactory]: a
         * factory of a runtime of its own over the same Redis, since the two caches share their keys.
         */
        fun create(
            store: CoCacheControlStore,
            factory: CoCacheFactory,
            legacyFactory: CoCacheFactory,
            keyPrefix: String,
        ): LegacyConversion = LegacyConversion(
            store,
            checkNotNull(factory.runtime.admin(CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME)) {
                "The back-references are not created through this factory."
            },
            legacyFactory.create(LegacyReferences.spec(keyPrefix)),
            checkNotNull(legacyFactory.runtime.admin(LegacyReferences.CACHE_NAME)),
        )
    }
}
