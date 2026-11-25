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

package me.ahoo.cosec.cocache.shadow

import me.ahoo.cocache.api.Codec
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.document.json.StoredFormat
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import tools.jackson.core.JacksonException
import java.time.Instant
import java.time.format.DateTimeParseException

/** The instances that have a status, as a JSON object of instance id to the time it listed itself. */
internal object InstanceIndexCodec : Codec<Map<String, Instant>> {
    private const val WHAT = "instance index"

    override fun encode(value: Map<String, Instant>): ByteArray = CoSecJson.mapper.writeValueAsBytes(
        StoredFormat.write(CoSecJson.mapper.createObjectNode()).apply {
            putObject(
                "instances"
            ).apply { value.toSortedMap().forEach { (instance, at) -> put(instance, at.toString()) } }
        },
    )

    /**
     * `{"format": 1, "instances": {id: time}}`; the map of instance id to time before formats is format 1 (`CTL-53`),
     * told apart by a number at `format` or an object at `instances`, which no time is.
     */
    override fun decode(bytes: ByteArray): Map<String, Instant> {
        val node = try {
            CoSecJson.mapper.readTree(bytes)
        } catch (_: JacksonException) {
            throw SnapshotFormatException("The $WHAT is not JSON")
        }
        // The map before formats holds times, strings: a number at `format`, or an object at `instances`, is the record.
        val formatted = node.get(StoredFormat.FIELD)?.isNumber == true || node.get("instances")?.isObject == true
        val instances = if (formatted) {
            node.also { StoredFormat.read(it, ShadowRecordMembers.INSTANCE_INDEX, WHAT) }.get("instances")
        } else {
            node
        }
        val valid = instances != null && instances.isObject && instances.values().all { it.isString }
        if (!valid) throw SnapshotFormatException("The $WHAT is not an object of instance id to time")
        return instances.properties().associate { (instance, at) ->
            instance to try {
                Instant.parse(at.asString())
            } catch (_: DateTimeParseException) {
                throw SnapshotFormatException("The $WHAT holds a time that does not parse")
            }
        }
    }
}
