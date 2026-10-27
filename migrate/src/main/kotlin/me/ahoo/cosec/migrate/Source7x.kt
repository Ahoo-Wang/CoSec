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

package me.ahoo.cosec.migrate

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.TreeMap

/**
 * 7.x policy data as stored, before it is decoded: what `convert` reads from Redis ([CoSec7xReader]) or from an export
 * directory ([ExportDirectory]). Every entry keeps the bytes it was stored as and where it was found, so a rejection
 * names a place the operator can fix.
 */
class Source7x(
    /** Every policy, in the order the source lists them; ids may repeat in an export, which the converter rejects. */
    val policies: List<Entry>,
    /** The ids in the global policy index. */
    val globalIndex: Set<String>,
    val apps: List<App>,
    /** The schema marker of a Redis source (`{p}:meta:schema`); null for an export, which has none. */
    val schemaMarker: Marker?,
    /** Where the global policy index was read, for rejections that name it. */
    val globalIndexLocation: String,
    /** SHA-256 over every input read, in a fixed order. */
    val inputSha256: String,
    /** 7.x rate limit rules (`LocalRateLimitRuleLoader`'s JSON), in file order; their ids may repeat, which is rejected. */
    val rateLimitRules: List<Entry> = emptyList(),
) {
    /** This source with [rules] after its own rate limit rules; the input hash covers them too. */
    fun withRateLimitRules(rules: List<Entry>): Source7x {
        val input = mapOf("source" to inputSha256.encodeToByteArray()) + rules.associate { it.location to it.bytes }
        return Source7x(policies, globalIndex, apps, schemaMarker, globalIndexLocation, sha256(input), rateLimitRules + rules)
    }

    /** The schema marker at [location], or a null [value] when it is absent. */
    class Marker(val location: String, val value: String?)

    /** One stored value: [id] is the id its key or position names. */
    class Entry(val id: String, val location: String, val bytes: ByteArray)

    /** An app: its app permission, when it has one, and its grants in the order the source lists them. */
    class App(val appId: String, val location: String, val appPermission: Entry?, val grants: List<Grant>)

    /** The permission ids granted to [roleId] in [spaceId] (empty for the default space), as a stored JSON array. */
    class Grant(val roleId: String, val spaceId: String, val location: String, val bytes: ByteArray)

    companion object {
        /** SHA-256 over [input] in key order, each key and value length-prefixed. */
        fun sha256(input: Map<String, ByteArray>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            TreeMap(input).forEach { (key, value) ->
                val keyBytes = key.encodeToByteArray()
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(keyBytes.size).array())
                digest.update(keyBytes)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
                digest.update(value)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
