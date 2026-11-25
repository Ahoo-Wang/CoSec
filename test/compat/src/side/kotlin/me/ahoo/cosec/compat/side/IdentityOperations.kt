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

package me.ahoo.cosec.compat.side

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.document.identity.IdentityHeader
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.token.TokenSession
import java.time.Instant

/**
 * The `CoSec-Identity` header (MIX-1): `identity.write` writes one of the named samples, `identity.read` reads a
 * header as a receiver does and describes what it read.
 */
internal object IdentityOperations {
    private val AT: Instant = Instant.parse("2026-11-01T00:00:00Z")
    private val EXPIRES: Instant = Instant.parse("2099-01-01T00:00:00Z")

    val ALL: Map<String, (Map<String, String>) -> String> = mapOf(
        "identity.write" to { arguments -> IdentityHeader.write(sample(arguments.getValue("sample"))) },
        "identity.read" to { arguments -> describe(IdentityCodec.decode(arguments.getValue("header"))) },
    )

    /** The samples both versions can write: each a kind of caller a gateway forwards. */
    private fun sample(name: String): Identity = when (name) {
        "person" -> Identity(
            "acme", "alice", "order", "team-a", setOf("clerk"),
            Identity.Credential(Identity.Method.JWT, "token-1", EXPIRES, issuedAt = AT),
            AT, "request-1",
            memberships = Identity.Memberships(mapOf("team-b" to setOf("viewer")), listOf("extra")),
        )
        "workload" -> Identity(
            "acme", "robot", "order", "", setOf("worker"),
            Identity.Credential(Identity.Method.API_KEY, "key-1", EXPIRES, capped = true),
            AT, "request-2",
            memberships = Identity.Memberships.NONE,
        )
        "session" -> Identity(
            "acme", "alice", "order", "", setOf("clerk"),
            Identity.Credential(
                Identity.Method.JWT, "token-2", EXPIRES, issuedAt = AT,
                session = TokenSession("family-1", AT, 3),
            ),
            AT, "request-3",
            claims = mapOf("dept" to "sales"),
            memberships = Identity.Memberships.NONE,
        )
        "no-memberships" -> Identity(
            "acme", "alice", "order", "", setOf("clerk"),
            Identity.Credential(Identity.Method.JWT, "token-3", EXPIRES, issuedAt = AT),
            AT, "request-4",
        )
        else -> throw IllegalArgumentException("no sample $name")
    }

    /** What a receiver read, every member in one line, in the same words whichever version read it. */
    private fun describe(identity: Identity): String = buildString {
        append("tenant=").append(identity.tenant)
        append(";subject=").append(identity.subject)
        append(";app=").append(identity.app)
        append(";space=").append(identity.space)
        append(";roles=").append(identity.roles.sorted())
        val credential = identity.credential
        append(";method=").append(credential.method.value)
        append(";id=").append(credential.id)
        append(";expiresAt=").append(credential.expiresAt)
        append(";capped=").append(credential.capped)
        append(";credentialIssuedAt=").append(credential.issuedAt)
        append(";session=").append(credential.session?.let { "${it.id}/${it.authTime}/${it.generation}" })
        append(";issuedAt=").append(identity.issuedAt)
        append(";request=").append(identity.requestId)
        append(";claims=").append(identity.claims.toSortedMap())
        append(";memberships=").append(
            identity.memberships?.let { "${it.spaces.toSortedMap().mapValues { (_, roles) -> roles.sorted() }}+${it.attachments}" }
        )
    }
}
