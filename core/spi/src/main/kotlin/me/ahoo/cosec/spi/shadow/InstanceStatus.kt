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

package me.ahoo.cosec.spi.shadow

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Duration
import java.time.Instant

/** A document an instance has installed (RFC 0007 §6.5): its revision, and how long since it was last confirmed. */
@ExperimentalCoSecApi
class InstalledDocument(val revision: Long, val lag: Duration) {
    private fun fields(): List<Any?> = listOf(revision, lag)

    override fun equals(other: Any?): Boolean = other is InstalledDocument && other.fields() == fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String = "InstalledDocument(${fields().joinToString()})"
}

/** What an instance reported having installed, at [at] (RFC 0007 §6.5). */
@ExperimentalCoSecApi
class InstanceStatus(
    val instance: String,
    val at: Instant,
    val documents: Map<SnapshotKey, InstalledDocument>,
    /**
     * The document format level this instance reads (RFC 0010 C5): 8.0 is [FORMAT_LEVEL], and the status codec requires
     * it. The publish gate that compares it with a document's level arrives with the first document field that needs
     * one (8.1); until then it is carried and not read.
     */
    val formatLevel: Int = FORMAT_LEVEL,
) {
    private fun fields(): List<Any?> = listOf(instance, at, documents, formatLevel)

    override fun equals(other: Any?): Boolean = other is InstanceStatus && other.fields() == fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String = "InstanceStatus(${fields().joinToString()})"

    companion object {
        /**
         * The document format level of this version: 8.0 reads level 1. Not a `const`, so that code compiled against one
         * release reads the level of the release it runs on.
         */
        @JvmField
        @Suppress("MayBeConst") // on purpose: a const is inlined into the caller's class file
        val FORMAT_LEVEL: Int = 1
    }
}
