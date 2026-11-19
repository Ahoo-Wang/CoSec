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

package me.ahoo.cosec.webmvc

import jakarta.servlet.http.HttpServletRequest
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.engine.Engine
import me.ahoo.cosec.engine.EngineRequest
import me.ahoo.cosec.engine.Evaluation
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.webmvc.ServletRequests.getSecurityContext
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.security.Principal
import java.util.concurrent.CompletableFuture

/**
 * `PIP-28`: the request's principal is CoSec's if and only if CoSec authenticated the request. A request CoSec
 * authenticated carries CoSec's principal, replacing what a filter in front (Spring Security, container login) set, and
 * answers no container role for it; on a request CoSec treats as anonymous, what the request had is left as it was.
 */
class RequestPrincipalTest {
    private object Views : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            CompiledDocument.platform(emptyList(), emptyList()),
            key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }

    private val permit = Decision.Permit(
        StatementRef.Policy(PolicyId("p"), 0, "s", BindingRef.Bound(BindingId("g"), DocumentKind.PLATFORM))
    )

    private val alice = AuthenticatedPrincipal(
        PrincipalId("alice"),
        TenantId("acme"),
        MembershipSource.Claims(Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())),
    )

    private fun filter(result: AuthnResult) = PipelineServletFilter(
        Pipeline.builder(Authenticator { result }, SuspendingSnapshotSource(Views))
            .engine(object : Engine {
                override fun evaluate(request: EngineRequest, snapshot: SnapshotView, trace: TraceLevel) =
                    Evaluation(permit, null)
            })
            .build()
    )

    /** What the handler reads of the request, behind [filter], when [front] is what reached CoSec's filter. */
    private class Seen(val principal: Principal?, val remoteUser: String?, val admin: Boolean, val authType: String?)

    private fun seen(result: AuthnResult, front: MockHttpServletRequest): Pair<Seen, Principal?> {
        var seen: Seen? = null
        var context: Principal? = null
        filter(result).doFilter(front, MockHttpServletResponse()) { req, _ ->
            val handed = req as HttpServletRequest
            seen = Seen(handed.userPrincipal, handed.remoteUser, handed.isUserInRole("ADMIN"), handed.authType)
            context = handed.getSecurityContext()!!.principal
        }
        return seen!! to context
    }

    /** A request that a filter in front of CoSec, or the container's login, signed in as bob, an ADMIN. */
    private fun bobInFront() = MockHttpServletRequest("GET", "/orders").apply {
        userPrincipal = Principal { "bob" }
        remoteUser = "bob"
        authType = "BASIC"
        addUserRole("ADMIN")
    }

    @Test
    fun `a request CoSec authenticated carries CoSec's principal`() {
        val (seen, context) = seen(AuthnResult.Authenticated(alice, "test"), MockHttpServletRequest("GET", "/orders"))
        seen.principal.assert().isSameAs(context)
        seen.remoteUser.assert().isEqualTo("alice")
        seen.admin.assert().isFalse()
        seen.authType.assert().isNull()
    }

    @Test
    fun `CoSec's principal replaces another layer's, and answers none of its roles`() {
        val (seen, context) = seen(AuthnResult.Authenticated(alice, "test"), bobInFront())
        seen.principal.assert().isSameAs(context)
        seen.principal!!.name.assert().isEqualTo("alice")
        seen.remoteUser.assert().isEqualTo("alice")
        // bob is an ADMIN of the layer in front; the request is alice's, so no role of bob's answers for it.
        seen.admin.assert().isFalse()
        seen.authType.assert().isNull()
    }

    @Test
    fun `an anonymous request carries none of CoSec's, and keeps another layer's as it was`() {
        val (plain, _) = seen(AuthnResult.Anonymous, MockHttpServletRequest("GET", "/orders"))
        plain.principal.assert().isNull()
        plain.remoteUser.assert().isNull()
        plain.admin.assert().isFalse()
        plain.authType.assert().isNull()
        val (bob, context) = seen(AuthnResult.Anonymous, bobInFront())
        context!!.name.assert().isEqualTo("(0)")
        bob.principal!!.name.assert().isEqualTo("bob")
        bob.remoteUser.assert().isEqualTo("bob")
        bob.admin.assert().isTrue()
        bob.authType.assert().isEqualTo("BASIC")
    }
}
