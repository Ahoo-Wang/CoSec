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

package me.ahoo.cosec.runtime.attribute

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.AttributeStatus
import me.ahoo.cosec.engine.flat.ExternalValues

private val log = KotlinLogging.logger {}

/**
 * What the pipeline fetched for one request, stage A and stage B together: the paths it asked for, the values, and
 * the paths fetched without failure. Not thread-safe: the pipeline records each answer after awaiting it.
 */
internal class FetchedAttributes {
    private val values = HashMap<String, Any>()
    private val available = HashSet<String>()
    private val asked = HashSet<String>()

    /** Nothing asked for, so nothing available. */
    val isEmpty: Boolean get() = asked.isEmpty()

    fun asked(path: String): Boolean = path in asked

    fun value(path: String): Any? = values[path]

    fun available(path: String): Boolean = path in available

    /** Records what [registered] answered for [names]; null when it failed or did not answer in time. */
    fun record(registered: AttributeProviders.Registered, names: Set<String>, answer: Map<String, Any>?) {
        check(this !== NONE) { "A pipeline without attribute providers fetches nothing." }
        asked += names
        if (answer == null) {
            return
        }
        for (path in names) {
            val value = answer[path]
            if (value == null) {
                available += path
                continue
            }
            val attribute = registered.attributes.getValue(path)
            if (ExternalValues.fits(value, attribute.type)) {
                values[path] = value
                available += path
            } else {
                // Unavailable, never absent: a wrong type is the provider's defect, not "no value".
                log.warn { "${registered.provider} answered a ${value.javaClass.name} for $path, declared ${attribute.type}." }
            }
        }
    }

    fun toExternalValues(): ExternalValues = ExternalValues(values, available)

    /** The status of every path asked for, without its value: for decision events (RFC 0006 §9.2). */
    fun statuses(): Map<String, AttributeStatus> = asked.associateWith {
        when {
            it !in available -> AttributeStatus.UNAVAILABLE
            it in values -> AttributeStatus.PRESENT
            else -> AttributeStatus.ABSENT
        }
    }

    companion object {
        /** A pipeline's with no providers: nothing is asked, so nothing is ever recorded in it. */
        val NONE = FetchedAttributes()
    }
}
