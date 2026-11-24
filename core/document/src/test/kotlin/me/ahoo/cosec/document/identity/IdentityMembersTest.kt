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
import me.ahoo.cosec.document.format.DocumentFormats
import me.ahoo.cosec.document.format.MapType
import me.ahoo.cosec.document.format.ObjectType
import me.ahoo.cosec.document.identity.IdentityMembers.WhenAbsent
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.identity.IdentityFormatException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode

/**
 * `IDN-42`: every member of the identity header has a recorded meaning when absent, the format descriptor names the same
 * members, and the reader reads each absence as recorded.
 */
class IdentityMembersTest {
    /** A header with every member, each optional one present. */
    private val whole = """
        {"v":1,"tenant":"acme","sub":"alice","scope":{"app":"order","space":""},"roles":["clerk"],
         "memberships":{"spaces":{"emea":["auditor"]},"attachments":["suspended"]},
         "auth":{"method":"jwt","jti":"t1","exp":1793404800,"iat":1793404200,"capped":true,
                 "session":{"sid":"s1","auth_time":1793400000,"generation":2}},
         "iat":1793401200,"rid":"r1","claims":{"name":"Alice"},"crit":["v"]}
    """.trimIndent()

    /** [whole] without the member at [path]. */
    private fun without(path: String): ByteArray {
        val root = CoSecJson.mapper.readTree(whole) as ObjectNode
        val names = path.split('.')
        val parent = names.dropLast(1).fold(root) { node, name -> node.get(name) as ObjectNode }
        checkNotNull(parent.remove(names.last())) { "The sample has no member $path" }
        return CoSecJson.mapper.writeValueAsBytes(root)
    }

    /** The paths the descriptor names, down to an open map, whose keys are data rather than members. */
    private fun descriptorPaths(type: ObjectType, prefix: String = ""): Map<String, Boolean> =
        type.fields.flatMap { field ->
            val path = prefix + field.name
            val nested = (field.type as? ObjectType)?.let { descriptorPaths(it, "$path.") }.orEmpty()
            listOf(path to field.required) + nested.toList()
        }.toMap()

    @Test
    fun `the descriptor names exactly the recorded members, and a required one is one whose absence is refused - IDN-42`() {
        val described = descriptorPaths(DocumentFormats.identitySchema.root as ObjectType)

        described.keys.assert().isEqualTo(IdentityMembers.BY_PATH.keys)
        described.forEach { (path, required) ->
            (IdentityMembers.BY_PATH.getValue(path).whenAbsent == WhenAbsent.REFUSED).assert().isEqualTo(required)
        }
        // Open maps stop the walk: their keys are the identity's data, never members a reader must know.
        val claims = checkNotNull((DocumentFormats.identitySchema.root as ObjectType).field("claims"))
        claims.type.assert().isInstanceOf(MapType::class.java)
    }

    @Test
    fun `the sample holds every member, and reads - IDN-42`() {
        IdentityMembers.ALL.forEach { without(it.path) }
        IdentityCodec.decodeJson(whole.encodeToByteArray()).memberships.assert().isNotNull()
    }

    @Test
    fun `a member recorded as refused when absent does not read without it - IDN-42`() {
        IdentityMembers.ALL.filter { it.whenAbsent == WhenAbsent.REFUSED }.forEach { member ->
            assertThrows<IdentityFormatException>(member.path) { IdentityCodec.decodeJson(without(member.path)) }
        }
    }

    @Test
    fun `each optional member reads its absence as recorded - IDN-42`() {
        fun read(path: String): Identity = IdentityCodec.decodeJson(without(path))
        val optional = IdentityMembers.ALL.filter { it.whenAbsent != WhenAbsent.REFUSED }.map { it.path }
        optional.assert()
            .containsExactlyInAnyOrder("memberships", "auth.iat", "auth.capped", "auth.session", "claims", "crit")

        IdentityMembers.BY_PATH.getValue("memberships").whenAbsent.assert().isEqualTo(WhenAbsent.UNSTATED)
        read("memberships").memberships.assert().isNull()

        IdentityMembers.BY_PATH.getValue("auth.capped").whenAbsent.assert().isEqualTo(WhenAbsent.FALSE)
        read("auth.capped").credential.capped.assert().isFalse()

        IdentityMembers.BY_PATH.getValue("auth.session").whenAbsent.assert().isEqualTo(WhenAbsent.NONE)
        read("auth.session").credential.session.assert().isNull()

        IdentityMembers.BY_PATH.getValue("claims").whenAbsent.assert().isEqualTo(WhenAbsent.EMPTY)
        read("claims").claims.assert().isEmpty()

        // A session needs the credential's issue time, so the issue time is left out of a credential without one.
        IdentityMembers.BY_PATH.getValue("auth.iat").whenAbsent.assert().isEqualTo(WhenAbsent.UNKNOWN)
        val unsessioned = CoSecJson.mapper.readTree(without("auth.session")) as ObjectNode
        (unsessioned.get("auth") as ObjectNode).remove("iat")
        IdentityCodec.decodeJson(CoSecJson.mapper.writeValueAsBytes(unsessioned)).credential.issuedAt.assert().isNull()
    }

    @Test
    fun `the members whose being ignored would widen what a reader decides are the memberships, the cap and the claims - IDN-42`() {
        IdentityMembers.ALL.filter { it.ignoringWidens }.map { it.path }
            .assert().containsExactlyInAnyOrder("memberships", "auth.capped", "claims")
    }
}
