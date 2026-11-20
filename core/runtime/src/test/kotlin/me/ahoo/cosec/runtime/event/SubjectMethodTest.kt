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

package me.ahoo.cosec.runtime.event

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * UD-11: the decision event names how the principal authenticated in the words `CoSec-Identity` uses for the same
 * credential, so a listener and a service behind the gateway read the same word for the same request.
 */
class SubjectMethodTest {
    private class Capture : DecisionListener {
        override val name = "capture"
        override val delivery = Delivery.REQUIRED
        var event: DecisionEvent? = null

        override suspend fun onDecision(event: DecisionEvent) {
            this.event = event
        }
    }

    private fun methodOf(credential: Identity.Credential?, authenticatorName: String): String? {
        val principal = AuthenticatedPrincipal(
            PrincipalId("alice"),
            TenantId("acme"),
            MembershipSource.Claims(PipelineFixtures.claims()),
            emptyMap(),
            credential,
            breakGlass = credential?.method == Identity.Method.JWT,
            issuedAt = null,
            apiKeyCredential = credential?.method == Identity.Method.API_KEY,
            app = null,
            session = null,
        )
        val capture = Capture()
        val listeners = DecisionListeners(listOf(capture))
        try {
            runBlocking {
                PipelineFixtures.pipeline(authenticator(AuthnResult.Authenticated(principal, Identity.Method(authenticatorName)))) {
                    listeners(listeners)
                }.evaluate(facts("/orders/1"))
            }
        } finally {
            listeners.cancel()
        }
        return capture.event!!.subject.method
    }

    @Test
    fun `the event names the credential as the identity header does`() {
        methodOf(Identity.Credential(Identity.Method.JWT, "t1", null), "token").assert().isEqualTo("jwt")
        methodOf(Identity.Credential(Identity.Method.API_KEY, "k1", null), "anything").assert().isEqualTo("api-key")
        methodOf(Identity.Credential(Identity.Method("token"), "t1", null), "token").assert().isEqualTo("token")
        methodOf(Identity.Credential(Identity.Method("partner"), "t1", null), "token").assert().isEqualTo("partner")
    }

    @Test
    fun `a principal with no credential is named by its authenticator`() {
        methodOf(null, "session").assert().isEqualTo("session")
    }
}
