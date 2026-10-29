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

package me.ahoo.cosec.engine.pattern

import me.ahoo.cosec.model.Action

/**
 * An action pattern (RFC 0005 §5.1): `service:verb`, `service:*` or `*`. No other wildcard exists, so containment
 * between action patterns is trivial (§5.5). HTTP actions are `http:<METHOD>` and `http:*`.
 *
 * Service and verb compare exactly, case included: `http:get` is not `http:GET`.
 */
class ActionPattern private constructor(
    /** `null` for `*`. */
    val service: String?,
    /** `null` for `*` and `service:*`. */
    val verb: String?,
) {
    fun matches(action: Action): Boolean =
        (service == null || service == action.namespace) && (verb == null || verb == action.verb)

    override fun equals(other: Any?): Boolean = other is ActionPattern && other.service == service && other.verb == verb

    override fun hashCode(): Int = 31 * service.hashCode() + verb.hashCode()

    override fun toString(): String = when {
        service == null -> ANY_TEXT
        verb == null -> "$service$SEPARATOR$ANY_TEXT"
        else -> "$service$SEPARATOR$verb"
    }

    companion object {
        private const val SEPARATOR = ':'
        private const val ANY_TEXT = "*"
        private const val RESERVED = ":*"
        val ANY: ActionPattern = ActionPattern(null, null)

        /** Parses [pattern], or throws [InvalidPatternException]. */
        fun parse(pattern: String): ActionPattern {
            if (pattern == ANY_TEXT) {
                return ANY
            }
            val separator = pattern.indexOf(SEPARATOR)
            if (separator < 0) {
                throw InvalidPatternException(pattern, 0, "an action is service:verb, service:* or *")
            }
            val service = pattern.substring(0, separator)
            val verb = pattern.substring(separator + 1)
            requireName(pattern, service, 0)
            if (verb == ANY_TEXT) {
                return ActionPattern(service, null)
            }
            requireName(pattern, verb, separator + 1)
            return ActionPattern(service, verb)
        }

        private fun requireName(pattern: String, name: String, at: Int) {
            if (name.isEmpty()) {
                throw InvalidPatternException(pattern, at, "service and verb must not be empty")
            }
            for (index in name.indices) {
                val char = name[index]
                if (char in RESERVED || char.isWhitespace() || char.isISOControl()) {
                    throw InvalidPatternException(pattern, at + index, "'$char' is not allowed in a service or verb")
                }
            }
        }
    }
}
