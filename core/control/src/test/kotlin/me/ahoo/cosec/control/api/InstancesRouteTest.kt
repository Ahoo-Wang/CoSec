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

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * `CTL-89`: `GET /instances` answers the readers of the store (RFC 0014 R5) to a platform operator, as a read of the
 * platform document; a tenant's administrator is refused, the service profile serves no such route, and it is a GET.
 */
class InstancesRouteTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val service = ControlHttp(fixture.plane, ControlProfile.SERVICE)
    private val plato = fixture.engine.security(TenantId.PLATFORM, "plato")
    private val alice = fixture.engine.security(ControlPlaneTest.ACME, "alice", roles = listOf("admin"))

    private fun call(security: RequestSecurity, method: String = "GET", on: ControlHttp = http): Pair<Int, JsonNode?> =
        runBlocking {
            val response = on.handle(ControlHttpRequest(method, "/instances", emptyMap(), ByteArray(0)), security)
            response.status to response.body.takeIf { it.isNotEmpty() }?.let(CoSecJson.mapper::readTree)
        }

    @Test
    fun `a platform operator reads the instances and their releases - CTL-89`() {
        val (status, body) = call(plato)
        status.assert().isEqualTo(200)
        checkNotNull(body)
        body["instances"].isArray.assert().isTrue()
        body["unreadable"].isArray.assert().isTrue()
        body["releases"].isObject.assert().isTrue()
        body["asOf"].isString.assert().isTrue()
    }

    @Test
    fun `a tenant's administrator is refused, and the service profile serves no such route`() {
        call(alice).first.assert().isEqualTo(403)
        call(plato, on = service).first.assert().isEqualTo(404)
    }

    @Test
    fun `it is a GET`() {
        call(plato, method = "POST").first.assert().isEqualTo(400)
    }
}
