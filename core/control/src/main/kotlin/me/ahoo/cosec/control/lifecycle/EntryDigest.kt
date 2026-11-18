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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.RoleDefinition
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.engine.RateLimitSource
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.decision.DocumentKind
import tools.jackson.databind.JsonNode
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The digest an operation's `ifMatch` names: SHA-256 of the entry as the document encodes it, in lowercase hex. Two
 * entries with the same digest are written the same way, so they mean the same.
 */
@InternalCoSecApi
object EntryDigest {
    fun of(policy: PolicyDefinition): String = sha256(PolicyDocumentCodec.policyTree(policy))

    fun of(binding: Binding, kind: DocumentKind, owner: String): String =
        sha256(PolicyDocumentCodec.bindingTree(binding, kind, owner))

    fun of(rule: RateLimitSource): String = sha256(PolicyDocumentCodec.rateLimitTree(rule))

    fun of(role: RoleDefinition): String = sha256(PolicyDocumentCodec.roleTree(role))

    internal fun sha256(tree: JsonNode): String = hex(CoSecJson.mapper.writeValueAsBytes(tree))

    internal fun hex(bytes: ByteArray): String =
        HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Lower-case hex, a byte at a time: the fixed cost of `String.format` per byte showed in the diff of large documents. */
    private val HEX: HexFormat = HexFormat.of()
}
