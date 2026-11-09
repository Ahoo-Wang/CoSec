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

package me.ahoo.cosec.spi.token

import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The rules of a token's claims hold wherever a [VerifiedToken] is made, on every path that verifies a token. */
class VerifiedTokenTest {
    private val invalid = listOf<() -> VerifiedToken>(
        { VerifiedToken("", "alice", null, null) },
        { VerifiedToken("jti", "", null, null) },
    )

    /** `IDN-11`. */
    @Test
    fun verifyAndRefreshRefuseAnEmptyTenant() {
        assertThrows<TokenVerificationException> { VerifiedToken("jti", "alice", "", null) }
    }

    /** RC1-52, `IDN-10`: a token claiming an id with an unpaired surrogate is invalid, so no store is asked about it. */
    @Test
    fun verifyAndRefreshRefuseAnIdWithAnUnpairedSurrogate() {
        val tokens = listOf<() -> VerifiedToken>(
            { VerifiedToken("jti\uD800", "alice", null, null) },
            { VerifiedToken("jti", "alice\uDC00", null, null) },
            { VerifiedToken("jti", "alice", "beta\uD800", null) },
            { VerifiedToken("jti", "alice", null, null, roles = setOf("r\uD800")) },
            { VerifiedToken("jti", "alice", null, null, policies = setOf("p\uD800")) },
        )
        tokens.forEach { assertThrows<TokenVerificationException> { it() } }
    }

    /** `IDN-12`. */
    @Test
    fun aSubjectBeginningWithAParenthesisIsRefused() {
        for (subject in listOf("(0)", "(platform)", "(")) {
            assertThrows<TokenVerificationException> { VerifiedToken("jti", subject, null, null) }
        }
        VerifiedToken("jti", "a(b)", null, null).principal.assert().isEqualTo("a(b)")
    }

    /** `SCP-10`, RFC 0012 §3.5. */
    @Test
    fun anAppThatCannotBeAnAppIdIsRefused() {
        val invalidApps = listOf(
            "",
            "*",
            "a:b",
            " ",
            "a\nb",
            "shop\uD800",
            "x".repeat(ScopeResolver.MAX_APP_ID_LENGTH + 1)
        )
        for (app in invalidApps) {
            assertThrows<TokenVerificationException> { VerifiedToken("jti", "alice", null, app) }
        }
        VerifiedToken("jti", "alice", null, "order").app.assert().isEqualTo("order")
        VerifiedToken("jti", "alice", null, null).app.assert().isNull()
    }

    @Test
    fun aTokenHasAnIdAndASubject() {
        invalid.forEach { assertThrows<TokenVerificationException> { it() } }
    }

    @Test
    fun itHoldsCopiesNobodyCanChange() {
        val roles = linkedSetOf("admin")
        val policies = linkedSetOf("p1")
        val attributes = linkedMapOf<String, Any>("a" to "b")
        val token = VerifiedToken("jti", "alice", null, null, roles, policies, attributes)
        roles += "root"
        policies += "p2"
        attributes["c"] = "d"
        token.roles.assert().containsExactly("admin")
        token.policies.assert().containsExactly("p1")
        token.attributes.assert().containsOnlyKeys("a")
        assertThrows<UnsupportedOperationException> { (token.roles as MutableSet<String>).add("root") }
        assertThrows<UnsupportedOperationException> { (token.attributes as MutableMap<String, Any>)["x"] = "y" }
    }
}
