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
import me.ahoo.cosec.jwt.JwtSigning
import me.ahoo.cosec.jwt.JwtTokenConverter
import me.ahoo.cosec.jwt.JwtTokenVerifier
import me.ahoo.cosec.login.token.TokenSubject
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.token.VerifiedToken
import java.util.UUID

/**
 * CoSec's tokens (MIX-2, MIX-3): `token.issue` issues the pair of a named subject, signed with `secret`, as
 * `<access> <refresh>`; `token.verify` verifies a token as a request's access token and describes what it holds.
 */
internal object TokenOperations {
    private val ids = IdGenerator { UUID.randomUUID().toString() }

    val ALL: Map<String, (Map<String, String>) -> String> = mapOf(
        "token.issue" to { arguments ->
            val pair = JwtTokenConverter(ids, JwtSigning.hmac256(arguments.getValue("secret")))
                .toToken(subject(arguments.getValue("sample")))
            "${pair.accessToken} ${pair.refreshToken}"
        },
        "token.verify" to { arguments ->
            describe(JwtTokenVerifier(JwtSigning.hmac256(arguments.getValue("secret"))).verify(arguments.getValue("token")))
        },
    )

    private fun subject(name: String): TokenSubject = when (name) {
        "person" -> Kinds.person("alice", "acme", "order", setOf("clerk", "viewer@team-a"), mapOf("dept" to "sales"))
        "platform" -> Kinds.person("plato", "(platform)", "cosec-control", setOf("admin"), emptyMap())
        else -> throw IllegalArgumentException("no sample $name")
    }

    private fun describe(token: VerifiedToken): String = buildString {
        append("principal=").append(token.principal)
        append(";tenant=").append(token.tenant)
        append(";app=").append(token.app)
        append(";roles=").append(token.roles.sorted())
        append(";policies=").append(token.policies.sorted())
        append(";attributes=").append(token.attributes.toSortedMap())
        append(";membership=").append(token.membershipMarker)
        append(";session=").append(token.session?.let { "${it.id}/${it.generation}" })
        append(";expires=").append(token.expiresAt != null)
    }
}
