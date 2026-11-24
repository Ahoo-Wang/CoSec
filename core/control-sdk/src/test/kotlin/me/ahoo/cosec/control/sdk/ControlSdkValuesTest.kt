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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.SecurityScope
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/** The facade's own values (`CTL-65`): what a caller holds, how a state reads, and what a refusal carries. */
class ControlSdkValuesTest {
    private val context = object : SecurityContext {
        override val tenantId = "acme"
        override val principal: CoSecPrincipal = object : CoSecPrincipal {
            override val id = "alice"
            override val authenticated = true
            override val attributes: Map<String, Any> = emptyMap()
        }
        override val scope: SecurityScope get() = error("not read")
        override val forwardedIdentity = null
    }

    @Test
    fun `a caller holds the request's context as it is, or a service principal with its roles`() {
        val of = ControlCaller.of(context)
        of.context.assert().isSameAs(context)
        of.principal.assert().isNull()
        of.toString().assert().isEqualTo("ControlCaller(alice@acme)")
        val service = ControlCaller.service("svc-iam", setOf("auditor"))
        service.context.assert().isNull()
        service.principal.assert().isEqualTo("svc-iam")
        service.roles.assert().containsExactly("auditor")
        service.toString().assert().isEqualTo("ControlCaller(service svc-iam)")
        assertThrownBy<IllegalArgumentException> { ControlCaller.service(" ") }
        ControlCaller.NOT_COSEC_CONTEXT.assert().contains("PIP-29")
    }

    /** DR1-F-18: a service caller is held in process to what `CoSec-Identity` holds it to over HTTP. */
    @Test
    fun `a service caller that CoSec-Identity would not carry is refused when made`() {
        val refused = listOf(
            { ControlCaller.service("") },
            { ControlCaller.service("\t") },
            { ControlCaller.service("svc\uD800") },
            { ControlCaller.service("svc-iam", setOf("")) },
            { ControlCaller.service("svc-iam", setOf("auditor", "r\uDC00")) },
        )
        refused.forEach { assertThrownBy<IllegalArgumentException> { it() }.message().doesNotContain("?") }
        val spaced = ControlCaller.service(" svc ", setOf("a b", "auditor@team"))
        spaced.roles.assert().containsExactly("a b", "auditor@team")
    }

    @Test
    fun `a state this version knows reads as itself, and one it does not keeps its text`() {
        ChangeState.KNOWN.forEach { ChangeState.of(it.text).assert().isSameAs(it) }
        ChangeState.READY.isUnknown.assert().isFalse()
        val later = ChangeState.of("MERGED")
        later.isUnknown.assert().isTrue()
        later.text.assert().isEqualTo("MERGED")
        later.toString().assert().isEqualTo("MERGED")
        later.assert().isEqualTo(ChangeState.of("MERGED"))
        later.hashCode().assert().isEqualTo(ChangeState.of("MERGED").hashCode())
        later.assert().isNotEqualTo(ChangeState.READY)
    }

    @Test
    fun `a refusal carries the route's problem and reads its members`() {
        val refused = ControlRefusedException(
            409,
            """{"type":"https://cosec.ahoo.me/problems/conflict","code":"CONFLICT","detail":"moved","requestId":"r1"}""",
        )
        refused.status.assert().isEqualTo(409)
        refused.code.assert().isEqualTo("CONFLICT")
        refused.detail.assert().isEqualTo("moved")
        refused.requestId.assert().isEqualTo("r1")
        refused.type.assert().isEqualTo("https://cosec.ahoo.me/problems/conflict")
        refused.message.orEmpty().assert().contains("409").contains("CONFLICT")
        ControlRefusedException(500, "not json").code.assert().isNull()
    }
}
