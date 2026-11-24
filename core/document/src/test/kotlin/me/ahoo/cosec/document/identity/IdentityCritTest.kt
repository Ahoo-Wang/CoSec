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
import me.ahoo.cosec.spi.identity.IdentityFormatException
import me.ahoo.cosec.spi.identity.IdentityFormatException.Reason
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.Base64

/**
 * `IDN-41`: a reader ignores a member it does not know, at any level, unless `crit` names it; a member `crit` names that
 * it does not know is refused as a later version's (503), and a `crit` that is not one is malformed (400).
 */
class IdentityCritTest {
    /** A header with every member this version knows, and [extra] members appended to its root. */
    private fun header(
        extra: String = "",
        scope: String = "",
        auth: String = "",
        session: String = "",
        memberships: String = "",
    ) =
        """{"v":1,"tenant":"acme","sub":"alice","scope":{"app":"order","space":""$scope},"roles":["clerk"],""" +
            """"memberships":{"spaces":{},"attachments":["suspended"]$memberships},""" +
            """"auth":{"method":"jwt","jti":"t1","exp":1793404800,"iat":1793404200,""" +
            """"session":{"sid":"s1","auth_time":1793400000,"generation":2$session}$auth},""" +
            """"iat":1793401200,"rid":"r1"$extra}"""

    private fun read(json: String) = IdentityCodec.decodeJson(json.encodeToByteArray())

    private fun reason(json: String): Reason = assertThrows<IdentityFormatException> { read(json) }.reason

    @Test
    fun `a member this version does not know is ignored at every level, and the identity reads as without it - IDN-41`() {
        val plain = read(header())
        listOf(
            header(extra = ""","kind":"person","future":{"a":[1,2]}"""),
            header(scope = ""","region":"emea""""),
            header(auth = ""","kind":"workload""""),
            header(session = ""","device":"d1""""),
            header(memberships = ""","groups":["g1"]"""),
        ).forEach { json -> read(json).assert().describedAs(json).isEqualTo(plain) }
    }

    @Test
    fun `an unknown member must still be JSON, and a duplicate is refused - IDN-41`() {
        assertThrows<IdentityFormatException> { read(header(extra = ""","future":{""")) }
            .reason.assert().isEqualTo(Reason.MALFORMED)
        // A duplicate is refused wherever it is, as the parser refuses it before any member is read.
        reason(header(extra = ""","future":1,"future":2""")).assert().isEqualTo(Reason.MALFORMED)
    }

    @Test
    fun `crit naming members this version knows and the header holds reads as without it - IDN-41`() {
        val plain = read(header())
        read(header(extra = ""","crit":["memberships","auth.session.sid","auth","crit"]""")).assert().isEqualTo(plain)
        read(header(extra = ""","crit":[]""")).assert().isEqualTo(plain)
    }

    @Test
    fun `crit naming a member this version does not know refuses the header as a later version's - IDN-41`() {
        listOf(
            ""","kind":"person","crit":["kind"]""",
            ""","crit":["auth.device"]""",
            ""","crit":["memberships","memberships.groups"]""",
            // A path into a map whose keys are data names no member.
            ""","claims":{"x":1},"crit":["claims.x"]""",
        ).forEach { extra ->
            reason(header(extra = extra)).assert().describedAs(extra).isEqualTo(Reason.UNSUPPORTED_MEMBER)
        }
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '\'',
        textBlock = """
        '"memberships"'                    | $.crit is not an array
        '{}'                               | $.crit is not an array
        '[1]'                              | $.crit[0] is not a string
        '[""]'                             | $.crit[0] is empty
        '["Kind"]'                         | $.crit[0] is not the path of a member
        '["auth..kind"]'                   | $.crit[0] is not the path of a member
        '["auth.kind."]'                   | $.crit[0] is not the path of a member
        '["memberships","memberships"]'    | $.crit[1] repeats a member
        '["claims"]'                       | $.crit[0] names a member the header does not hold""",
    )
    fun `a crit that is not one is malformed, before any member it names is looked up - IDN-41`(
        crit: String,
        reason: String,
    ) {
        val error = assertThrows<IdentityFormatException> { read(header(extra = ""","crit":$crit""")) }
        error.reason.assert().isEqualTo(Reason.MALFORMED)
        error.message.assert().contains(reason)
    }

    @Test
    fun `a crit of more than sixteen entries is malformed - IDN-41`() {
        val entries = (0 until IdentityCrit.MAX_ENTRIES + 1).joinToString(",") { "\"m$it\"" }
        reason(header(extra = ""","crit":[$entries]""")).assert().isEqualTo(Reason.MALFORMED)
    }

    @Test
    fun `a malformed entry is refused as malformed even beside an unknown one - IDN-41`() {
        reason(header(extra = ""","crit":["kind","Bad"]""")).assert().isEqualTo(Reason.MALFORMED)
    }

    @Test
    fun `CoSec's writers write no crit - IDN-41`() {
        val written = IdentityCodec.encode(read(header()))
        String(Base64.getUrlDecoder().decode(written)).assert().doesNotContain("\"crit\"")
    }
}
