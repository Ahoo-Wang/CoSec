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

package me.ahoo.cosec.migrate

import me.ahoo.cocache.api.Codec
import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cocache.core.StoreSpec
import me.ahoo.cocache.core.policy.UnknownReadMode
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKeyText
import tools.jackson.core.JacksonException

/**
 * The back-references as stores before 8.0.0-beta.5 kept them, one value per policy, at the keys the sets now use:
 * `<p>:control:refs:<docKey>:<policyId>`, a JSON object of the referrers' keys, sorted, each with the revision of it
 * that last indexed the reference (#1207): `{"tenant:acme": 3}`. What [LegacyConversion] reads to convert them.
 */
internal object LegacyReferences {
    /** The cache name of [spec]: not one of CoSec's, created without CoSec's locks, in a runtime of its own. */
    const val CACHE_NAME = "LegacyReferenceIndexCache"

    private const val WHAT = "back-reference value of the format before 8.0.0-beta.5"

    /** `<docKey>:<policyId>`, as the sets' keys are encoded. */
    fun encode(target: SnapshotKey, policy: PolicyId): String {
        require(Identifiers.isWellFormed(policy.value)) {
            "Policy [${Identifiers.printable(policy.value)}] names no reference key."
        }
        return "${SnapshotKeyEncoder.encode(target)}:${policy.value}"
    }

    /** A read-only `Store` over the keys of the back-references under [keyPrefix]; a failed read throws. */
    fun spec(keyPrefix: String): StoreSpec<Pair<SnapshotKey, PolicyId>, Map<SnapshotKey, Long>> {
        val layout = CoSecCacheSpecs.layout(keyPrefix, CoSecCacheSpecs.REFERENCE_INDEX_CACHE_NAME)
        return StoreSpec.builder<Pair<SnapshotKey, PolicyId>, Map<SnapshotKey, Long>>(CACHE_NAME)
            .keyPrefix(layout.keyPrefix)
            .namespace(layout.namespace)
            .keyEncoder(KeyEncoder { (target, policy) -> encode(target, policy) })
            .codec(Format)
            .unknownRead(UnknownReadMode.THROW)
            .readOnly()
            .build()
    }

    /** The old format's codec: what it writes is what 8.0.0-beta.4 wrote, byte for byte. */
    object Format : Codec<Map<SnapshotKey, Long>> {
        override fun encode(value: Map<SnapshotKey, Long>): ByteArray {
            val node = CoSecJson.mapper.createObjectNode()
            value.entries.map { (key, revision) -> SnapshotKeyEncoder.encode(key) to revision }.sortedBy { it.first }
                .forEach { (key, revision) -> node.put(key, revision) }
            return CoSecJson.mapper.writeValueAsBytes(node)
        }

        /** Anything but the object form, a member that is not a document key, or a negative revision, is malformed. */
        override fun decode(bytes: ByteArray): Map<SnapshotKey, Long> {
            val root = try {
                CoSecJson.mapper.readTree(bytes)
            } catch (error: JacksonException) {
                throw SnapshotFormatException("The $WHAT is not JSON.", error)
            }
            if (root == null || !root.isObject) malformed("it is not an object")
            return root.properties().associateTo(LinkedHashMap()) { (text, revision) ->
                val value = if (revision.isIntegralNumber && revision.canConvertToLong()) {
                    revision.asLong()
                } else {
                    malformed("the revision of [$text] is not an integer")
                }
                if (value < 0) malformed("revision $value of [$text] is negative")
                (SnapshotKeyEncoder.decode(text) ?: malformed("[$text] is not a document key")) to value
            }
        }

        private fun malformed(reason: String): Nothing =
            throw SnapshotFormatException("The $WHAT is malformed: ${SnapshotKeyText.printable(reason)}.")
    }
}
