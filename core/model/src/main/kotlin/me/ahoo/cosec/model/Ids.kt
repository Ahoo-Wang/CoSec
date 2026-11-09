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

package me.ahoo.cosec.model

/**
 * The identifiers of the unified model (RFC 0004 §4.1). They appear on API boundaries only: compiled structures keep
 * plain strings, because collections of value classes box every element.
 */
@JvmInline
value class TenantId(val value: String) {
    companion object {
        /** Same as `Tenant.PLATFORM_TENANT_ID`. */
        val PLATFORM = TenantId("(platform)")

        /** Same as `CoSec.DEFAULT`: the tenant of anonymous principals. */
        val DEFAULT = TenantId("(0)")
    }
}

@JvmInline
value class PrincipalId(val value: String)

/** Never [Match.WILDCARD]: documents write `*` for [Match.Any], so a literal `*` app would decode as every app. */
@JvmInline
value class AppId(val value: String) {
    init {
        require(value != Match.WILDCARD) { "App id must not be the wildcard [${Match.WILDCARD}]." }
    }
}

/** Never [Match.WILDCARD], for the same reason as [AppId]. */
@JvmInline
value class SpaceId(val value: String) {
    init {
        require(value != Match.WILDCARD) { "Space id must not be the wildcard [${Match.WILDCARD}]." }
    }

    companion object {
        /** The default space: the empty id. */
        val DEFAULT = SpaceId("")
    }
}

@JvmInline
value class RoleId(val value: String)

@JvmInline
value class PolicyId(val value: String)

@JvmInline
value class BindingId(val value: String)

/**
 * The text rule every kind of id follows, wherever it enters CoSec (RC1-52, `IDN-10`): no unpaired UTF-16 surrogate.
 *
 * UTF-8 has no encoding for one. The clients that write Redis keys from a string (a template, a key encoder) put `?` in
 * its place, so the tenant `beta\uD800` and the tenant `beta?` would name the same Redis key, and one would read the other's
 * documents, memberships and revocations. An id with one is refused where it enters (a token, an API key, a provider's
 * answer, a control request), so no two ids ever share a stored key.
 */
object Identifiers {
    /** Whether [text] holds no unpaired UTF-16 surrogate: every high surrogate is followed by a low one, and only so. */
    fun isWellFormed(text: CharSequence): Boolean {
        var index = 0
        val length = text.length
        while (index < length) {
            if (text[index].isSurrogate()) {
                if (isUnpairedSurrogateAt(text, index)) {
                    return false
                }
                // The low half of the pair.
                index++
            }
            index++
        }
        return true
    }

    /** Whether every one of [texts] [isWellFormed]. */
    fun areWellFormed(texts: Iterable<String>): Boolean = texts.all(::isWellFormed)

    /** Whether every id [memberships] holds [isWellFormed]: its spaces, roles and attachments, and its malformed claims. */
    fun isWellFormed(memberships: Memberships): Boolean =
        memberships.roles.all { (space, roles) -> isWellFormed(space.value) && roles.all { isWellFormed(it.value) } } &&
            memberships.attachments.all { isWellFormed(it.value) } &&
            areWellFormed(memberships.malformed)

    /**
     * Whether the character of [text] at [index] is a surrogate without its other half: a high surrogate not followed by a
     * low one, or a low surrogate not preceded by a high one.
     */
    fun isUnpairedSurrogateAt(text: CharSequence, index: Int): Boolean {
        val c = text[index]
        return when {
            c.isHighSurrogate() -> index + 1 >= text.length || !text[index + 1].isLowSurrogate()
            c.isLowSurrogate() -> index == 0 || !text[index - 1].isHighSurrogate()
            else -> false
        }
    }

    /**
     * [text] for a message or a log line: a control character, which could forge a line (RC1-15), and an unpaired
     * surrogate, which a UTF-8 log would write as `?` and so name another id (RC1-52), as its `\uXXXX` escape.
     */
    fun printable(text: String): String {
        val first = text.indices.firstOrNull { text[it].isISOControl() || isUnpairedSurrogateAt(text, it) } ?: return text
        val printed = StringBuilder(text.length + ESCAPE_LENGTH).append(text, 0, first)
        for (index in first until text.length) {
            val c = text[index]
            if (c.isISOControl() || isUnpairedSurrogateAt(text, index)) {
                printed.append("\\u").append(c.code.toString(HEX).uppercase().padStart(ESCAPE_DIGITS, '0'))
            } else {
                printed.append(c)
            }
        }
        return printed.toString()
    }

    private const val HEX = 16
    private const val ESCAPE_DIGITS = 4
    private const val ESCAPE_LENGTH = 6
}
