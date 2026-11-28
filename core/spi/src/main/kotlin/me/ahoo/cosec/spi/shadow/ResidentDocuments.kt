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

package me.ahoo.cosec.spi.shadow

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Instant

/**
 * The documents [instance] holds, by key, with the revision of each it has installed, as of [at] (RFC 0007 §6.5,
 * `CTL-75`): what `propagation` reads. Large (a gateway holds thousands of tenants), so it is a record of its own beside
 * the [InstanceStatus]: written when what it holds changed since the last write that succeeded, and refreshed before
 * its life ends, never on every report interval.
 */
@InternalCoSecApi
class ResidentDocuments(val instance: String, val at: Instant, val documents: Map<SnapshotKey, Long>) {
    private fun fields(): List<Any?> = listOf(instance, at, documents)

    override fun equals(other: Any?): Boolean = other is ResidentDocuments && other.fields() == fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String = "ResidentDocuments(${fields().joinToString()})"
}
