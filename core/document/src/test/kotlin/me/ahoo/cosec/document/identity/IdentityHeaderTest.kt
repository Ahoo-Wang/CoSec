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
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/** The header a service writes is the header a gate reads (`CTL-65`, design §0.10): written once, read the same. */
class IdentityHeaderTest {
    private val now = Instant.parse("2026-11-21T00:00:00Z")
    private val identity = Identity(
        tenant = "(platform)",
        subject = "iam",
        app = "cosec-control",
        space = "",
        roles = setOf("onboarding"),
        credential = Identity.Credential(
            Identity.Method.API_KEY,
            Identity.Credential.Kind.WORKLOAD,
            "service:iam",
            now.plusSeconds(30)
        ),
        issuedAt = now,
        requestId = "r1",
    )

    @Test
    fun `a written header reads back as the identity written`() {
        IdentityHeader.NAME.assert().isEqualTo("CoSec-Identity")
        IdentityCodec.decode(IdentityHeader.write(identity)).assert().isEqualTo(identity)
    }

    @Test
    fun `a header a receiver would refuse is never written`() {
        val forever = identity.copy(
            credential = Identity.Credential(Identity.Method.API_KEY, Identity.Credential.Kind.WORKLOAD, "k", null)
        )
        assertThrownBy<IllegalStateException> { IdentityHeader.write(forever) }
        assertThrownBy<IllegalStateException> { IdentityHeader.write(identity, maxBytes = 16) }
        assertThrownBy<IllegalArgumentException> { IdentityHeader.write(identity.copy(subject = "")) }
    }
}
