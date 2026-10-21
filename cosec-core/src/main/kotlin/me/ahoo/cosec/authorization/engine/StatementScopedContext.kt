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

package me.ahoo.cosec.authorization.engine

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.cosec.policy.action.PATH_VARIABLES_KEY

/**
 * The security context the legacy matchers see during one evaluation (RFC 0002 §6.2).
 *
 * Path variables live in a field that [beginStatement] resets before every guard and rule, so a matcher only ever
 * sees the variables its own statement's action captured: never another statement's, layer's or rate limit rule's,
 * and the caller's context never sees any. Every other attribute is read and written on [base], as before, so the
 * parsed request path cache and the policy evaluation mark are shared by all matchers of the request.
 *
 * One per evaluation; the engine calls guards and rules one at a time on one thread, so it needs no synchronization.
 */
internal class StatementScopedContext(private val base: SecurityContext) : SecurityContext {
    override val principal: CoSecPrincipal get() = base.principal
    override val tenant: Tenant get() = base.tenant

    /** Null after a matcher removed the variables, as removing the attribute would. */
    private var pathVariables: Any? = EMPTY

    private var attributesView: ScopedAttributes? = null

    override val attributes: MutableMap<String, Any>
        get() = attributesView ?: ScopedAttributes().also { attributesView = it }

    /** Called before every guard and rule: one field write, nothing written to [base]. */
    fun beginStatement() {
        pathVariables = EMPTY
    }

    override fun <V> getAttributeValue(attributeKey: String): V? {
        if (attributeKey == PATH_VARIABLES_KEY) {
            @Suppress("UNCHECKED_CAST")
            return pathVariables as V?
        }
        return base.getAttributeValue(attributeKey)
    }

    override fun setAttributeValue(attributeKey: String, value: Any): SecurityContext {
        if (attributeKey == PATH_VARIABLES_KEY) {
            pathVariables = value
            return this
        }
        base.setAttributeValue(attributeKey, value)
        return this
    }

    override fun toString(): String = base.toString()

    /**
     * [base]'s attributes with the path variables taken from the field. Iterating it yields [base]'s entries
     * except its path variables, then the field's.
     */
    private inner class ScopedAttributes : AbstractMutableMap<String, Any>() {
        private val baseAttributes: MutableMap<String, Any> get() = base.attributes

        override val size: Int
            get() {
                val baseSize = baseAttributes.size - if (baseAttributes.containsKey(PATH_VARIABLES_KEY)) 1 else 0
                return baseSize + if (pathVariables == null) 0 else 1
            }

        override fun containsKey(key: String): Boolean =
            if (key == PATH_VARIABLES_KEY) pathVariables != null else baseAttributes.containsKey(key)

        override fun get(key: String): Any? = if (key == PATH_VARIABLES_KEY) pathVariables else baseAttributes[key]

        override fun put(key: String, value: Any): Any? {
            if (key != PATH_VARIABLES_KEY) {
                return baseAttributes.put(key, value)
            }
            val previous = pathVariables
            pathVariables = value
            return previous
        }

        override fun remove(key: String): Any? {
            if (key != PATH_VARIABLES_KEY) {
                return baseAttributes.remove(key)
            }
            val previous = pathVariables
            pathVariables = null
            return previous
        }

        override val entries: MutableSet<MutableMap.MutableEntry<String, Any>>
            get() = Entries()

        private inner class Entries : AbstractMutableSet<MutableMap.MutableEntry<String, Any>>() {
            override val size: Int get() = this@ScopedAttributes.size

            override fun add(element: MutableMap.MutableEntry<String, Any>): Boolean =
                throw UnsupportedOperationException("Use put instead.")

            override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, Any>> = EntryIterator()
        }

        private inner class EntryIterator : MutableIterator<MutableMap.MutableEntry<String, Any>> {
            private val baseEntries = baseAttributes.entries.iterator()
            private var next: MutableMap.MutableEntry<String, Any>? = null
            private var pathVariablesReturned = false
            private var lastWasPathVariables = false

            override fun hasNext(): Boolean {
                while (next == null && baseEntries.hasNext()) {
                    val entry = baseEntries.next()
                    if (entry.key != PATH_VARIABLES_KEY) {
                        next = entry
                    }
                }
                return next != null || (!pathVariablesReturned && pathVariables != null)
            }

            override fun next(): MutableMap.MutableEntry<String, Any> {
                if (!hasNext()) {
                    throw NoSuchElementException()
                }
                next?.let {
                    next = null
                    lastWasPathVariables = false
                    return it
                }
                pathVariablesReturned = true
                lastWasPathVariables = true
                return PathVariablesEntry()
            }

            override fun remove() {
                if (lastWasPathVariables) {
                    pathVariables = null
                } else {
                    baseEntries.remove()
                }
            }
        }

        private inner class PathVariablesEntry : MutableMap.MutableEntry<String, Any> {
            override val key: String get() = PATH_VARIABLES_KEY
            override val value: Any get() = checkNotNull(pathVariables) { "The path variables were removed." }

            override fun setValue(newValue: Any): Any {
                val previous = value
                pathVariables = newValue
                return previous
            }

            override fun equals(other: Any?): Boolean =
                other is Map.Entry<*, *> && other.key == key && other.value == pathVariables

            override fun hashCode(): Int = key.hashCode() xor pathVariables.hashCode()

            override fun toString(): String = "$key=$pathVariables"
        }
    }

    private companion object {
        val EMPTY: Map<String, String> = emptyMap()
    }
}
