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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.IpAddress

/**
 * The external attributes one evaluation reads (RFC 0006 §5.4): what the pipeline fetched for the [AttributePlan].
 *
 * An attribute is available when it was fetched without failure: its value is in [values], or it has none and is
 * absent (a comparison with it is false, RFC 0005 §3.4). Anything else is missing, whether its provider failed or
 * timed out or it was never planned, and a statement reading it follows its [OnMissing]. A value whose type is not the
 * attribute's declared one is missing too: it never reads as absent.
 */
class ExternalValues internal constructor(
    /** By path: a `String`, `Boolean`, `Set<String>` or [IpAddress], as the attribute's type says. */
    private val values: Map<String, Any>,
    /** The paths fetched without failure. */
    private val available: Set<String>,
    /** Tests only: records every path an evaluation reads, for P14. */
    private val reads: MutableSet<String>?,
) {
    constructor(values: Map<String, Any>, available: Set<String>) : this(values, available, null)

    /** How a path stands: for the trace and audit (RFC 0006 §5.4.3). */
    fun status(path: String): AttributeStatus = when {
        path !in available -> AttributeStatus.UNAVAILABLE
        path in values -> AttributeStatus.PRESENT
        else -> AttributeStatus.ABSENT
    }

    /** The value of [attribute], null when absent; throws [AttributeMissing] when it is missing. */
    internal fun read(attribute: Attribute): Any? {
        val path = attribute.path
        reads?.add(path)
        if (path !in available) {
            throw AttributeMissing(path)
        }
        val value = values[path] ?: return null
        if (!fits(value, attribute.type)) {
            throw AttributeMissing(path)
        }
        return value
    }

    companion object {
        /** Nothing fetched: every external attribute is missing. */
        val NONE: ExternalValues = ExternalValues(emptyMap(), emptySet())

        /** Whether [value] is of [type]. */
        fun fits(value: Any, type: AttributeType): Boolean = when (type) {
            AttributeType.STRING -> value is String
            AttributeType.BOOL -> value is Boolean
            AttributeType.STRING_SET -> value is Set<*> && value.all { it is String }
            AttributeType.IP -> value is IpAddress
            else -> false
        }
    }
}

enum class AttributeStatus { PRESENT, ABSENT, UNAVAILABLE }

/**
 * An external attribute a condition read is missing. A statement's error, like any exception of a condition, but one
 * that [OnMissing] decides. Carries no stack trace: it is an expected outcome, not a defect.
 */
internal class AttributeMissing(val path: String) : RuntimeException(
    "External attribute $path is unavailable",
    null,
    false,
    false
)
