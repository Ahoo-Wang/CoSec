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

package me.ahoo.cosec.document.identity

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.spi.identity.Identity
import tools.jackson.databind.JsonNode

/**
 * The `memberships` member of the identity header (`IDN-39`), read and written for [IdentityCodec]: each other space
 * with its roles, then the attachments. The identity's own space is not one of the spaces (its roles are `roles`), nor
 * the wildcard, which is no space; a space names at least one role; an attachment is an id, never repeated.
 */
internal object IdentityMemberships {
    private val FIELDS = arrayOf("spaces", "attachments")

    /** [node] as the memberships of an identity in [space]; [IdentityCodec.malformed] when it is not this format. */
    @Suppress("ThrowsCount")
    fun read(node: JsonNode, space: String): Identity.Memberships = with(IdentityCodec) {
        node.only("$.memberships", *FIELDS)
        val spaces = node.get("spaces") ?: throw malformed("[memberships.spaces] is missing")
        if (!spaces.isObject) throw malformed("$.memberships.spaces is not an object")
        val bySpace = LinkedHashMap<String, Set<String>>()
        for ((name, roles) in spaces.properties()) {
            val path = spacePath(name)
            if (!Identifiers.isWellFormed(name)) throw malformed("$path holds an unpaired surrogate")
            if (name == space) throw malformed("$path is the identity's own space, whose roles are $.roles")
            if (name == Match.WILDCARD) throw malformed("$path is the wildcard, which is no space")
            bySpace[name] = roles(roles, path).ifEmpty { throw malformed("$path names no role") }
        }
        val attachments = attachments(
            node.get("attachments") ?: throw malformed("[memberships.attachments] is missing")
        )
        if (bySpace.isEmpty() && attachments.isEmpty()) {
            Identity.Memberships.NONE
        } else {
            Identity.Memberships(bySpace, attachments)
        }
    }

    /** The path of space [name] under `memberships.spaces`, quoted as every name a header chooses is. */
    fun spacePath(name: String): String = "$.memberships.spaces[\"" + QuotedText.of(name) + "\"]"

    private fun attachments(node: JsonNode): List<String> = with(IdentityCodec) {
        if (!node.isArray) throw malformed("$.memberships.attachments is not an array")
        if (node.isEmpty) return@with emptyList()
        val attachments = LinkedHashSet<String>()
        node.values().forEachIndexed { i, policy ->
            if (!attachments.add(policy.id("$.memberships.attachments[$i]"))) {
                throw malformed("$.memberships.attachments[$i] repeats a policy")
            }
        }
        attachments.toList()
    }
}
