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
import me.ahoo.cosec.spi.identity.Identity.Credential.Kind
import me.ahoo.cosec.spi.identity.IdentityFormatException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.Base64

/**
 * `IDN-44`: `auth.kind` says whose the credential is. CoSec's writers always write it; a reader takes `person` as a
 * person's and anything else, or nothing, as a workload's; a credential named `api-key` or `service` declared a
 * person's does not read.
 */
class IdentityKindTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z")

    private fun identity(method: Identity.Method, kind: Kind) = Identity(
        tenant = "acme",
        subject = "alice",
        app = "",
        space = "",
        roles = setOf("clerk"),
        credential = Identity.Credential(method, kind, "c1", now.plusSeconds(600)),
        issuedAt = now,
        requestId = "r1",
    )

    private fun auth(kind: String?, method: String = "jwt"): ByteArray {
        val member = kind?.let { ""","kind":$it""" }.orEmpty()
        return """{"v":1,"tenant":"acme","sub":"alice","scope":{"app":"","space":""},"roles":[],""".encodeToByteArray() +
            """"auth":{"method":"$method"$member,"jti":"c1","exp":1793404800},"iat":1793401200,"rid":"r1"}""".encodeToByteArray()
    }

    @Test
    fun `every writer of CoSec writes the kind, and it reads back - IDN-44`() {
        listOf(
            identity(Identity.Method.JWT, Kind.PERSON) to "\"kind\":\"person\"",
            identity(Identity.Method.JWT, Kind.WORKLOAD) to "\"kind\":\"workload\"",
            identity(Identity.Method.SERVICE, Kind.WORKLOAD) to "\"kind\":\"workload\"",
            identity(Identity.Method("ldap"), Kind.PERSON) to "\"kind\":\"person\"",
        ).forEach { (identity, member) ->
            val header = IdentityCodec.encode(identity)
            String(Base64.getUrlDecoder().decode(header)).assert().contains(member)
            IdentityCodec.decode(header).assert().isEqualTo(identity)
        }
    }

    @Test
    fun `no kind, or one this version does not know, is a workload's - IDN-44`() {
        listOf(null, "\"workload\"", "\"agent\"", "\"PERSON\"", "\"\"").forEach { kind ->
            IdentityCodec.decodeJson(auth(kind)).credential.kind.assert().describedAs("$kind").isEqualTo(Kind.WORKLOAD)
        }
        IdentityCodec.decodeJson(auth("\"person\"")).credential.kind.assert().isEqualTo(Kind.PERSON)
    }

    @Test
    fun `a kind that is not a string, or an api-key or service declared a person's, does not read - IDN-44`() {
        listOf(
            auth("1"),
            auth("{}"),
            auth("\"person\"", method = "api-key"),
            auth("\"person\"", method = "service"),
        ).forEach { json ->
            assertThrows<IdentityFormatException> { IdentityCodec.decodeJson(json) }
                .reason.assert().isEqualTo(IdentityFormatException.Reason.MALFORMED)
        }
    }
}
