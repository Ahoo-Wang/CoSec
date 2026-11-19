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

@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.testkit

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * UC-7: a run of the kit cannot pass for the wrong reason. Documents that never loaded and scenarios leaning on the
 * format's example names fail, each saying what was not found.
 */
class VacuousRunTest {
    private val header = """"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z""""
    private val platform = """{$header,"policies":[{"id":"deny-delete","statements":[{"effect":"deny","action":"order:delete"}]}],""" +
        """"bindings":[{"id":"deny-delete","subject":"*","app":"*","space":"*","policy":"deny-delete"}]}"""
    private val acme = """{$header,"policies":[{"id":"read","statements":[{"effect":"allow","action":"order:read"}]}],""" +
        """"bindings":[{"id":"read","subject":"*","app":"*","space":"*","policy":"read"}]}"""

    private fun scenario(tenant: String, extra: String = "") =
        """{"credential":{"tenant":"$tenant","id":"alice"},$extra""" +
            """"request":{"action":"order:delete","resource":{"type":"order","id":"1"}},"expect":{"decision":"Deny"}}"""

    @Test
    fun `a directory without a platform document does not load`(@TempDir directory: Path) {
        assertThrows<ScenarioFormatException> { PolicyTestKit.load(directory) }.message.assert()
            .contains("No platform.json").contains("write an empty document there")
        Files.writeString(directory.resolve("platform.json"), "{$header}")
        PolicyTestKit.load(directory)
    }

    @Test
    fun `what the kit would not read fails loading, named`(@TempDir directory: Path) {
        Files.writeString(directory.resolve("platform.json"), platform)
        Files.createDirectories(directory.resolve("tenant"))
        Files.writeString(directory.resolve("tenant/acme.json"), acme)
        Files.writeString(directory.resolve("acme.json"), acme)
        Files.createDirectories(directory.resolve("tenants"))
        Files.writeString(directory.resolve("tenants/globex.yaml"), "")
        assertThrows<ScenarioFormatException> { PolicyTestKit.load(directory) }.message.assert()
            .contains("[acme.json, tenant, tenants/globex.yaml]").contains("tenants/<tenant>.json")
        // What it does not look at stays out of the way: hidden files, and anything but .json beside the documents.
        Files.delete(directory.resolve("tenant/acme.json"))
        Files.delete(directory.resolve("tenant"))
        Files.delete(directory.resolve("acme.json"))
        Files.delete(directory.resolve("tenants/globex.yaml"))
        Files.writeString(directory.resolve("README.md"), "")
        Files.writeString(directory.resolve(".hidden.json"), "")
        PolicyTestKit.load(directory)
    }

    @Test
    fun `a scenario names who asks and what`() {
        val kit = PolicyTestKit.of(platform)
        assertThrows<ScenarioFormatException> { kit.evaluate("""{"expect":{"decision":"Deny"}}""") }.message.assert()
            .contains("credential (tenant and id").contains("request.action (the example's is order:read)")
            .contains("request.resource (the example's is order/1)")
        assertThrows<ScenarioFormatException> {
            kit.evaluate(
                """{"credential":{"id":"alice"},"request":{"action":"order:read","resource":{"type":"order","id":"1"}}}"""
            )
        }.message.assert().contains("credential.tenant (the example's is acme)")
        kit.evaluate(
            """{"credential":{"anonymous":true},"request":{"action":"order:read","resource":{"type":"order","id":"1"}}}"""
        )
    }

    @Test
    fun `among tenant documents, a scenario's tenant has one, or says it has none`() {
        val kit = PolicyTestKit.of(platform, tenants = mapOf("acme" to acme))
        kit.check("acme", scenario("acme"))
        // A file named for another id (Acme.json) leaves acme without one: the Deny would pass on an empty document.
        val misplaced = PolicyTestKit.of(platform, tenants = mapOf("Acme" to acme))
        assertThrows<ScenarioFormatException> { misplaced.check("acme", scenario("acme")) }.message.assert()
            .contains("tenant [acme], which has no document here (tenants/acme.json)").contains("\"withoutDocument\"")
        misplaced.check("on purpose", scenario("acme", """"withoutDocument":["tenant"],"""))
        assertThrows<ScenarioFormatException> {
            misplaced.check("a typo", scenario("acme", """"withoutDocument":["tenants"],"""))
        }.message.assert().contains("withoutDocument names tenant or app")
    }

    @Test
    fun `platform-only documents, and the anonymous tenant, decide without a tenant document`() {
        PolicyTestKit.of(platform).check("platform only", scenario("acme"))
        val kit = PolicyTestKit.of(platform, tenants = mapOf("acme" to acme))
        kit.check(
            "anonymous",
            """{"credential":{"anonymous":true},"request":{"action":"order:delete","resource":{"type":"order","id":"1"}},""" +
                """"expect":{"decision":"Deny"}}""",
        )
    }

    @Test
    fun `among app documents, a scenario's app has one, or says it has none`() {
        val app = """{$header,"policies":[],"bindings":[]}"""
        val kit = PolicyTestKit.of(platform, apps = mapOf("shop" to app))
        val inWms = """{"credential":{"tenant":"acme","id":"alice","app":"wms"},"request":{"hostApp":"wms",""" +
            """"action":"order:delete","resource":{"type":"order","id":"1"}},"expect":{"decision":"Deny"}}"""
        assertThrows<ScenarioFormatException> { kit.check("wms", inWms) }.message.assert()
            .contains("app [wms], which has no document here (apps/wms.json)")
        kit.check("wms on purpose", inWms.replaceFirst("{", """{"withoutDocument":["app"],"""))
    }
}
