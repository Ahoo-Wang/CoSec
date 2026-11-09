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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.testkit

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText

class PipelineFixtureTest {
    private val anyone = """{"policies":[{"id":"public","statements":[{"effect":"allow","action":"http:GET","resource":"http:path:/public/**"}]}],""" +
        """"bindings":[{"id":"public","subject":"*","app":"*","space":"*","policy":"public"}]}"""

    private val tenant = """{"policies":[{"id":"reads","statements":[{"effect":"allow","action":"http:GET","resource":"http:path:/orders/**"}]}],""" +
        """"bindings":[{"id":"reads","subject":{"role":"reader"},"app":"*","space":"*","policy":"reads"}]}"""

    private fun fixture() = PipelineFixture.of(platform = anyone, tenants = mapOf("acme" to tenant))

    private val reader = PipelineFixture.Principal("alice", tenant = "acme", roles = setOf("reader"))

    @Test
    fun `an anonymous request is decided by the platform document`() = runBlocking<Unit> {
        fixture().use {
            it.evaluate(it.request("GET", "/public/x")).proceed.assert().isTrue()
            val denied = it.evaluate(it.request("GET", "/orders/1"))
            denied.proceed.assert().isFalse()
            denied.status.assert().isEqualTo(401)
        }
    }

    @Test
    fun `a principal's roles are decided by its tenant's document`() = runBlocking<Unit> {
        fixture().use {
            it.evaluate(it.request("GET", "/orders/1", reader)).status.assert().isEqualTo(200)
            val stranger = PipelineFixture.Principal("bob", tenant = "acme")
            it.evaluate(it.request("GET", "/orders/1", stranger)).status.assert().isEqualTo(403)
        }
    }

    @Test
    fun `a permitted request has the security context a handler reads`() = runBlocking<Unit> {
        fixture().use {
            val context = it.securityContext(it.request("GET", "/orders/1", reader))
            context.principal.id.assert().isEqualTo("alice")
            context.tenantId.assert().isEqualTo("acme")
            // The context answers further checks through the same pipeline.
            val checker = AuthorizationChecker(it.pipeline)
            checker.check(context, "http:GET", "/orders/2").assert().isTrue()
            checker.check(context, "http:DELETE", "/orders/2").assert().isFalse()
        }
    }

    @Test
    fun `a refused request has no security context`() = runBlocking<Unit> {
        fixture().use {
            assertThrownBy<AssertionError> {
                runBlocking { it.securityContext(it.request("GET", "/orders/1")) }
            }.message().contains("401")
        }
    }

    @Test
    fun `a credential the fixture did not write is anonymous`() = runBlocking<Unit> {
        fixture().use {
            val forged = it.request("GET", "/orders/1", headers = mapOf("Authorization" to "Bearer fixture-1"))
            it.evaluate(forged).status.assert().isEqualTo(401)
        }
    }

    @Test
    fun `documents are read from a directory as the policy test kit reads them`(
        @TempDir directory: Path
    ) = runBlocking<Unit> {
        directory.resolve("platform.json").writeText(anyone)
        directory.resolve("tenants").createDirectory().resolve("acme.json").writeText(tenant)
        PipelineFixture.load(directory).use {
            it.evaluate(it.request("GET", "/orders/1", reader)).proceed.assert().isTrue()
            it.evaluate(it.request("GET", "/orders/1")).status.assert().isEqualTo(401)
        }
    }

    @Test
    fun `a document that does not compile fails when the fixture is built`() {
        assertThrownBy<DocumentException> {
            PipelineFixture.of(
                tenants = mapOf("acme" to """{"policies":[{"id":"bad","statements":[{"effect":"maybe"}]}]}""")
            )
        }
    }
}
