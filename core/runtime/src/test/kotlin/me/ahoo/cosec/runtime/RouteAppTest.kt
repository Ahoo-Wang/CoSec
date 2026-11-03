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

package me.ahoo.cosec.runtime

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.api.audit.AuditRequest
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * SCP-2, RC1-6: a gateway route's `cosec-app-id` is the host app of its requests, ahead of the configured one; the
 * gateway passes it as written, and the pipeline checks it under the app id rule (RC1-15). One that cannot be an app id
 * fails the request closed: 500, never a request without a host app, whose client would pick the app.
 */
class RouteAppTest {
    private val engine = PipelineFixtures.StubEngine(PipelineFixtures.PERMIT)
    private val pipeline = PipelineFixtures.pipeline(
        PipelineFixtures.authenticator(PipelineFixtures.authenticated()),
        engine,
    )

    private fun facts(routeApp: String?, declared: String? = null, host: String? = "shop") = HttpFacts(
        method = "GET",
        rawPath = "/orders",
        rawQuery = null,
        headers = Headers.of(declared?.let { mapOf(CoSecParameters.APP_ID to listOf(it)) }.orEmpty()),
        cookies = Cookies.EMPTY,
        remoteAddress = "10.0.0.1",
        host = HostScope(host?.let(::AppId)),
        requestAttributes = mapOf(AuditRequest.ROUTE_ID_ATTRIBUTE_KEY to "billing\nroute"),
        routeApp = routeApp,
    )

    private fun evaluate(facts: HttpFacts): Outcome = runBlocking { pipeline.evaluate(facts) }

    @Test
    fun `the route's app is the host app, ahead of the configured one`() {
        evaluate(facts("billing")).proceed.assert().isTrue()
        engine.requests.last().scope.app.value.assert().isEqualTo("billing")
        evaluate(facts("billing", declared = "billing")).proceed.assert().isTrue()
        // Another declared app is rejected, as against a configured host app.
        val other = evaluate(facts("billing", declared = "shop"))
        other.status.assert().isEqualTo(403)
        other.problem!!.type.assert().isEqualTo(ProblemType.SCOPE_REJECTED)
        // Without a route app the configured one applies, and without either the client's.
        evaluate(facts(null)).proceed.assert().isTrue()
        engine.requests.last().scope.app.value.assert().isEqualTo("shop")
        evaluate(facts(null, declared = "order", host = null)).proceed.assert().isTrue()
        engine.requests.last().scope.app.value.assert().isEqualTo("order")
    }

    @Test
    fun `a route app that cannot be an app id fails the request closed, and says why`() {
        for (app in listOf("", " ", "*", "a:b", "a\nb", "x".repeat(129))) {
            val before = engine.requests.size
            val outcome = evaluate(facts(app))
            outcome.status.assert().describedAs(app).isEqualTo(500)
            outcome.problem!!.type.assert().isEqualTo(ProblemType.INTERNAL_ERROR)
            engine.requests.size.assert().describedAs(app).isEqualTo(before)
            // What the log line says: the route, with its control characters escaped, and why.
            val error = (outcome.basis as Basis.Error).error
            error.message.assert().contains("route [billing\\u000Aroute]").contains("cannot be an app id")
                .doesNotContain("\n")
        }
    }
}
