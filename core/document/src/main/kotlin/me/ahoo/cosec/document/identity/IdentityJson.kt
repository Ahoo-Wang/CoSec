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
import me.ahoo.cosec.spi.identity.Identity
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.JacksonSerializable
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.jsontype.TypeSerializer
import java.math.BigDecimal
import java.math.BigInteger

/**
 * [identity] as the JSON [IdentityCodec] writes, streamed through the mapper's generator into its pooled buffers: the
 * gateway writes one on every forwarded request, and a tree of nodes would cost each member its nodes on top of its
 * text. Members in the format's order; roles, spaces and claims sorted, attachments in their order. What cannot be
 * written is refused by [check] first, so writing never throws past the mapper.
 */
internal class IdentityJson(private val identity: Identity) : JacksonSerializable.Base() {
    override fun serialize(gen: JsonGenerator, serializers: SerializationContext) {
        gen.writeStartObject()
        gen.writeNumberProperty("v", IdentityCodec.VERSION)
        gen.writeStringProperty("tenant", identity.tenant)
        gen.writeStringProperty("sub", identity.subject)
        gen.writeObjectPropertyStart("scope")
        gen.writeStringProperty("app", identity.app)
        gen.writeStringProperty("space", identity.space)
        gen.writeEndObject()
        gen.writeName("roles")
        strings(gen, identity.roles)
        identity.memberships?.let { memberships(gen, it) }
        credential(gen, identity.credential)
        gen.writeNumberProperty("iat", identity.issuedAt.epochSecond)
        gen.writeStringProperty("rid", identity.requestId)
        if (identity.claims.isNotEmpty()) {
            gen.writeObjectPropertyStart("claims")
            identity.claims.toSortedMap().forEach { (name, value) -> claim(gen, name, value) }
            gen.writeEndObject()
        }
        gen.writeEndObject()
    }

    override fun serializeWithType(gen: JsonGenerator, serializers: SerializationContext, typeSer: TypeSerializer) =
        serialize(gen, serializers)

    /** `memberships` (`IDN-39`): nothing but its text when there is nothing more, which most identities state. */
    private fun memberships(gen: JsonGenerator, memberships: Identity.Memberships) {
        gen.writeObjectPropertyStart("memberships")
        gen.writeObjectPropertyStart("spaces")
        val spaces = memberships.spaces
        if (spaces.isNotEmpty()) {
            for (name in if (spaces.size == 1) spaces.keys else spaces.keys.sorted()) {
                gen.writeName(name)
                strings(gen, spaces.getValue(name))
            }
        }
        gen.writeEndObject()
        gen.writeArrayPropertyStart("attachments")
        memberships.attachments.forEach(gen::writeString)
        gen.writeEndArray()
        gen.writeEndObject()
    }

    private fun credential(gen: JsonGenerator, credential: Identity.Credential) {
        gen.writeObjectPropertyStart("auth")
        gen.writeStringProperty("method", credential.method.value)
        gen.writeStringProperty("jti", credential.id)
        gen.writeNumberProperty("exp", checkNotNull(credential.expiresAt).epochSecond)
        credential.issuedAt?.let { gen.writeNumberProperty("iat", it.epochSecond) }
        if (credential.capped) {
            gen.writeBooleanProperty("capped", true)
        }
        credential.session?.let { session ->
            gen.writeObjectPropertyStart("session")
            gen.writeStringProperty("sid", session.id)
            gen.writeNumberProperty("auth_time", session.authTime.epochSecond)
            gen.writeNumberProperty("generation", session.generation)
            gen.writeEndObject()
        }
        gen.writeEndObject()
    }

    /** [values] sorted, as an array; one value needs no sorting. */
    private fun strings(gen: JsonGenerator, values: Set<String>) {
        gen.writeStartArray()
        (if (values.size <= 1) values else values.sorted()).forEach(gen::writeString)
        gen.writeEndArray()
    }

    private fun claim(gen: JsonGenerator, name: String, value: Any) {
        when (value) {
            is String -> gen.writeStringProperty(name, value)
            is Boolean -> gen.writeBooleanProperty(name, value)
            else -> when (val exact = exactly(value as Number)) {
                is Long -> gen.writeNumberProperty(name, exact)
                else -> gen.writeNumberProperty(name, exact as Double)
            }
        }
    }

    companion object {
        /** What [IdentityCodec.encode] cannot write, refused before anything is: a credential without expiry, a claim. */
        fun check(identity: Identity) {
            identity.credential.expiresAt ?: throw Unencodable.neverExpires(identity.subject)
            if (identity.claims.isNotEmpty()) {
                identity.claims.forEach { (name, value) -> checkClaim(name, value) }
            }
        }

        private fun checkClaim(name: String, value: Any) {
            when (value) {
                is String -> if (value.length > IdentityCodec.MAX_CLAIM_LENGTH) {
                    throw Unencodable.claimTooLong(name, value.length)
                }
                is Boolean -> Unit
                is Number -> exactly(value) ?: throw Unencodable.claimInexact(name, value)
                else -> throw Unencodable.claimType(name, value)
            }
        }

        /**
         * A number claim as the header's JSON holds it, which a service reads back as a `Long` or a `Double` (#1228): the
         * standard integer types, and a `BigInteger` within a `Long`, as an integer; a finite `Float` or `Double`, and a
         * `BigDecimal` that a `Double` holds exactly (its shortest decimal is the same number), as a number. Anything else
         * is refused rather than rounded: no other `Number` is converted, since its value could change on the way. Null
         * when it cannot hold it exactly.
         */
        private fun exactly(value: Number): Number? = when (value) {
            is Int, is Long, is Short, is Byte -> value.toLong()
            is BigInteger -> if (value.bitLength() < Long.SIZE_BITS) value.toLong() else null
            is Double, is Float -> value.toDouble().takeIf { it.isFinite() }
            // Its shortest decimal, which is what the header writes, must be the same number.
            is BigDecimal -> value.toDouble().takeIf { it.isFinite() && BigDecimal.valueOf(it).compareTo(value) == 0 }
            else -> null
        }
    }
}
