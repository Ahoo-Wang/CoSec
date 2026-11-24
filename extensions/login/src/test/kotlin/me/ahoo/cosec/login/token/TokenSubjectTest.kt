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

package me.ahoo.cosec.login.token

import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class TokenSubjectTest {
    /** Drill 2, finding 12: a host without apps names no app, as it names no tenant. */
    @Test
    fun `app and tenant default to none`() {
        val subject = TokenSubject("alice", Identity.Credential.Kind.PERSON)
        subject.app.assert().isNull()
        subject.tenant.assert().isNull()
        subject.assert().isEqualTo(
            TokenSubject(id = "alice", kind = Identity.Credential.Kind.PERSON, app = null, tenant = null)
        )
    }

    @Test
    fun `a subject equals one with the same fields, and says them`() {
        val subject = TokenSubject("alice", Identity.Credential.Kind.PERSON, app = "shop", roles = setOf("clerk"))
        subject.assert().isEqualTo(
            TokenSubject("alice", Identity.Credential.Kind.PERSON, app = "shop", tenant = null, roles = setOf("clerk"))
        )
        subject.hashCode().assert().isEqualTo(
            TokenSubject(
                "alice",
                Identity.Credential.Kind.PERSON,
                app = "shop",
                tenant = null,
                roles = setOf("clerk")
            ).hashCode()
        )
        subject.assert().isNotEqualTo(TokenSubject("alice", Identity.Credential.Kind.PERSON))
        subject.toString().assert().contains("alice").contains("shop").contains("roles=1")
    }

    @Test
    fun `its text never holds the attributes, which an application fills freely`() {
        val subject = TokenSubject(
            "alice",
            Identity.Credential.Kind.PERSON,
            attributes = mapOf("email" to "alice@example.com")
        )
        subject.toString().assert().doesNotContain("alice@example.com").doesNotContain("email").contains("attributes=1")
    }

    /** IDN-45: whose the token is is part of the subject, and the session it is issued in keeps it. */
    @Test
    fun `a subject's kind is part of it, and its session keeps it - IDN-45`() {
        val machine = TokenSubject("svc", Identity.Credential.Kind.WORKLOAD)
        machine.assert().isNotEqualTo(TokenSubject("svc", Identity.Credential.Kind.PERSON))
        machine.toString().assert().contains("kind=WORKLOAD")
        val issue = TokenFamilies.Issue(
            me.ahoo.cosec.spi.token.TokenSession("s1", java.time.Instant.EPOCH, 1),
            java.time.Instant.EPOCH.plusSeconds(60),
        )
        machine.inSession(issue).kind.assert().isEqualTo(Identity.Credential.Kind.WORKLOAD)
    }
}
