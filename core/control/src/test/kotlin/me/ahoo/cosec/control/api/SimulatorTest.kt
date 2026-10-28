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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.runtime.snapshot.MissingPlatformException
import me.ahoo.cosec.snapshot.document.PolicyDocument
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** `simulate` and `explain` decide on the view enforcement would read (S5, `CTL-3`). */
class SimulatorTest {
    private val request = SimulatedRequest(
        tenant = TenantId("acme"),
        principal = "alice",
        action = Action("order", "read"),
        resource = Resource("order", "1", emptyMap()),
        time = 0,
    )

    private fun document(kind: DocumentKind, owner: String, body: String): PolicyDocument =
        PolicyDocumentCodec.decode(
            kind,
            owner,
            """{"schema":2,"revision":1,"publishedAt":"2026-10-27T00:00:00Z"$body}""".encodeToByteArray(),
        )

    private fun decide(simulator: Simulator, documents: Documents): Decision {
        val scope = (simulator.scope(request) as ScopeResolution.Resolved).scope
        return simulator.evaluate(request, scope, documents).decision
    }

    @Test
    fun `an absent platform document fails closed`() {
        val tenant = document(DocumentKind.TENANT, "acme", ALLOW_ALL)
        val decision = decide(Simulator(), Documents(null, null, tenant))
        decision.assert().isInstanceOf(Decision.Indeterminate::class.java)
        ((decision as Decision.Indeterminate).cause as Cause.SourceUnavailable).error
            .assert().isInstanceOf(MissingPlatformException::class.java)
    }

    @Test
    fun `a document that does not compile is indeterminate, not an error`() {
        val platform = document(DocumentKind.PLATFORM, "", "")
        // An ext. attribute no provider declares does not compile.
        val tenant = document(DocumentKind.TENANT, "acme", IN_FENCE)
        val decision = decide(Simulator(), Documents(platform, null, tenant))
        decision.assert().isInstanceOf(Decision.Indeterminate::class.java)
        ((decision as Decision.Indeterminate).cause as Cause.SourceUnavailable).document
            .assert().isEqualTo(DocumentKind.TENANT)
    }

    @Test
    fun `documents compile against the host's attribute providers`() {
        val inFence = Attribute.external("ext.geo.inFence", AttributeType.BOOL)
        val catalog = AttributeCatalog { path -> inFence.takeIf { path == it.path } }
        val platform = document(DocumentKind.PLATFORM, "", "")
        val tenant = document(DocumentKind.TENANT, "acme", IN_FENCE)
        // The attribute is not fetched in a simulation: the statement reading it is indeterminate, but it compiled.
        val decision = decide(Simulator(attributes = catalog), Documents(platform, null, tenant))
        ((decision as? Decision.Indeterminate)?.cause is Cause.SourceUnavailable).assert().isFalse()
    }

    private companion object {
        const val ALLOW_ALL = ""","policies":[{"id":"all","statements":[{"effect":"allow","action":"*"}]}],""" +
            """"bindings":[{"id":"all","subject":"*","app":"*","space":"*","policy":"all"}]"""
        const val IN_FENCE = ""","policies":[{"id":"fence","statements":[{"effect":"allow","action":"*",""" +
            """"condition":{"eq":["ext.geo.inFence",true]}}]}],""" +
            """"bindings":[{"id":"fence","subject":"*","app":"*","space":"*","policy":"fence"}]"""
    }
}
