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

package me.ahoo.cosec.runtime.identity

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * `IDN-34`: the identity a service states for itself when it calls the control plane names its credential `service`,
 * never `api-key` and never `jwt`; admitted as the gate admits a forwarded identity, its principal is a workload's, with
 * no break-glass and no session. The same identity is written into `CoSec-Identity` and read back unchanged.
 */
class ServiceIdentityTest {
    private val now = Instant.parse("2026-11-23T00:00:00Z")
    private val identity = ServiceIdentity.of("svc-iam", setOf("projector"), "cosec-control", now, "r1")

    @Test
    fun `a service's identity names its credential service - IDN-34`() {
        identity.credential.method.assert().isEqualTo(Identity.Method.SERVICE)
        identity.credential.id.assert().isEqualTo("service:svc-iam")
        identity.credential.session.assert().isNull()
        identity.tenant.assert().isEqualTo(TenantId.PLATFORM.value)
        val header = IdentityCodec.encode(identity)
        IdentityCodec.decode(header).credential.method.assert().isEqualTo(Identity.Method.SERVICE)
    }

    @Test
    fun `admitted, a service's identity is a workload's, without break-glass - IDN-34`() {
        val gate = IdentityGate(clock = Clock.fixed(now, ZoneOffset.UTC))
        val facts = HttpFacts("POST", "/", null, Headers.EMPTY, Cookies.EMPTY, "in-process", HostScope(null))
        val principal = (gate.admit(identity, "r1", facts) as IdentityGate.Admission.Admitted).security.principal!!
        principal.workload.assert().isTrue()
        principal.apiKey.assert().isFalse()
        principal.breakGlass.assert().isFalse()
        principal.session.assert().isNull()
    }
}
