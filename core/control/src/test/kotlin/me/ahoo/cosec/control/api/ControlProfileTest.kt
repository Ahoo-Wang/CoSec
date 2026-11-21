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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.model.TenantId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `CTL-58`: the service profile serves the tenant and app documents and the change flow that needs no person; anything
 * else is a 404 before the control plane is asked, for a principal that is allowed everything.
 */
class ControlProfileTest {
    private val fixture = ControlPlaneTest()
    private val plato = fixture.engine.security(TenantId.PLATFORM, "plato", policies = listOf("cosec:platform-admin"))
    private val operators = ControlHttp(fixture.plane)
    private val services = ControlHttp(fixture.plane, ControlProfile.SERVICE)

    private fun status(http: ControlHttp, method: String, path: String, body: String? = null): Int = runBlocking {
        http.handle(ControlHttpRequest(method, path, emptyMap(), body?.encodeToByteArray()), plato).status
    }

    @Test
    fun `the service profile serves a tenant document and its history, and the change flow`() {
        for (path in listOf(
            "/documents/tenant:acme",
            "/documents/tenant:acme/revisions",
            "/documents/tenant:acme/revisions/1",
            "/documents/tenant:acme/diff?from=1&to=1",
            "/changes",
            "/changes/c1",
        )) {
            status(services, "GET", path.substringBefore('?')).assert().describedAs("GET $path").isNotEqualTo(404)
        }
        for (path in listOf(
            "/changes",
            "/changes/c1:rebase",
            "/changes/c1:abandon",
            "/changes/c1:skip-shadow",
            "/changes/c1:publish",
            "/documents/tenant:acme:apply",
        )) {
            status(services, "POST", path, "{}").assert().describedAs("POST $path").isNotEqualTo(404)
        }
    }

    @Test
    fun `what is a person's or the platform's is a 404 on the service profile, and is served on the operators' one`() {
        val outside = listOf(
            "GET" to "/documents/platform",
            "GET" to "/documents/platform/revisions",
            "GET" to "/documents/tenant:acme/revisions/1/propagation",
            "POST" to "/documents/tenant:acme:rollback",
            "POST" to "/documents/app:order:apply",
            "POST" to "/documents/platform:apply",
            "POST" to "/changes/c1:shadow",
            "POST" to "/changes/c1:approve",
            "POST" to "/changes/c1:reject",
            "POST" to "/break-glass",
            "POST" to "/break-glass/c1:approve",
            "DELETE" to "/break-glass/b1",
            "POST" to "/simulate",
            "POST" to "/explain",
            "POST" to "/analyze",
        )
        for ((method, path) in outside) {
            status(services, method, path, "{}").assert().describedAs("service $method $path").isEqualTo(404)
        }
        // The same requests are not 404 on the operators' profile (they are answered, or refused for what they hold).
        for ((method, path) in outside.filter { it.second != "/documents/tenant:acme/revisions/1/propagation" }) {
            status(operators, method, path, "{}").assert().describedAs("operators $method $path").isNotEqualTo(404)
        }
    }

    @Test
    fun `a proposal for the platform document is a 404 on the service profile, never reaching the control plane`() {
        val body = """{"document":"platform","base":1,"operations":[]}"""
        status(services, "POST", "/changes", body).assert().isEqualTo(404)
        status(operators, "POST", "/changes", body).assert().isNotEqualTo(404)
        // A tenant document is served, and refused for what the body lacks, not for being outside the profile.
        status(services, "POST", "/changes", """{"document":"tenant:acme","base":1,"operations":[]}""")
            .assert().isNotEqualTo(404)
    }

    @Test
    fun `no principal is a 401 on the service profile as on the operators' one, before any route is looked at`() {
        val anonymous = runBlocking {
            services.handle(ControlHttpRequest("GET", "/documents/platform", emptyMap(), null), null).status
        }
        anonymous.assert().isEqualTo(401)
    }
}
