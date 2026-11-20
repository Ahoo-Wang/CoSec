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

package me.ahoo.cosec.cocache

import me.ahoo.cocache.api.Codec
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.document.json.StoredFormat
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.token.TokenFamily
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * A [TokenFamily], the session record, as JSON with its format (R2C-28, `IDN-31`): read by the reader's rule
 * ([StoredFormat]), so a member a later version adds is ignored and a later format is refused, which ends the session
 * (the refresh is refused and the user signs in again). A record written before formats is format 1. Anything
 * malformed is a [SnapshotFormatException], never an argument error the store would read as an outage.
 */
internal object TokenFamilyCodec : Codec<TokenFamily> {
    private const val WHAT = "token family"

    override fun encode(value: TokenFamily): ByteArray {
        val node = StoredFormat.write(CoSecJson.mapper.createObjectNode())
        node.put("id", value.id)
        node.put("tenant", value.tenant.value)
        node.put("principal", value.principal.value)
        node.put("authTime", value.authTime.toString())
        node.put("expiresAt", value.expiresAt.toString())
        node.put("generation", value.generation)
        node.put("rotatedAt", value.rotatedAt?.toString())
        node.put("revoked", value.revoked)
        return CoSecJson.mapper.writeValueAsBytes(node)
    }

    override fun decode(bytes: ByteArray): TokenFamily {
        val node = try {
            CoSecJson.mapper.readTree(bytes)
        } catch (_: JacksonException) {
            throw SnapshotFormatException("A stored $WHAT is not JSON.")
        }
        StoredFormat.of(node, WHAT)
        val generation = node.need("generation")
        if (!generation.isIntegralNumber || !generation.canConvertToLong()) {
            malformed(
                "its generation is not an integer"
            )
        }
        val revoked = node.need("revoked")
        if (!revoked.isBoolean) malformed("its revoked is not a boolean")
        return TokenFamily(
            id = node.text("id"),
            tenant = TenantId(node.text("tenant")),
            principal = PrincipalId(node.text("principal")),
            authTime = node.instant("authTime"),
            expiresAt = node.instant("expiresAt"),
            generation = generation.asLong(),
            rotatedAt = node.get("rotatedAt")?.takeUnless { it.isNull }?.let { node.instant("rotatedAt") },
            revoked = revoked.booleanValue(),
        )
    }

    private fun JsonNode.need(field: String): JsonNode = get(field) ?: malformed("its $field is missing")

    private fun JsonNode.text(field: String): String = need(
        field
    ).let { if (it.isString) it.asString() else malformed("its $field is not a string") }

    private fun JsonNode.instant(field: String): Instant = try {
        Instant.parse(text(field))
    } catch (_: DateTimeParseException) {
        malformed("its $field is not an instant")
    }

    private fun malformed(reason: String): Nothing = throw SnapshotFormatException(
        "A stored $WHAT is malformed: $reason."
    )
}
