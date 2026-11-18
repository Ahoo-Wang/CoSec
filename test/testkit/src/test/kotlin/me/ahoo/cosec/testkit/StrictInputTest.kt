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
 * What the kit reads is read as production reads it: a field twice in an object is refused where it is first parsed
 * (#1406), and two files of one directory that hold one document are refused, naming both (#1407). Otherwise a test
 * passes on documents a host would refuse, or on fewer documents than the directory holds.
 */
class StrictInputTest {
    private val platform = """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}"""
    private val duplicated = """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","policies":[],"policies":[]}"""

    @Test
    fun `a field twice in a document is refused by the kit and by the fixture`() {
        assertThrows<ScenarioFormatException> { PolicyTestKit.of(duplicated) }.message.assert().contains("policies")
        assertThrows<ScenarioFormatException> { PipelineFixture.of(duplicated) }.message.assert().contains("policies")
        PolicyTestKit.of(platform)
    }

    @Test
    fun `a field twice in an app or tenant document is refused too`() {
        assertThrows<ScenarioFormatException> { PolicyTestKit.of(platform, apps = mapOf("wms" to duplicated)) }
        assertThrows<ScenarioFormatException> { PolicyTestKit.of(platform, tenants = mapOf("acme" to duplicated)) }
        assertThrows<ScenarioFormatException> { PipelineFixture.of(platform, tenants = mapOf("acme" to duplicated)) }
    }

    @Test
    fun `a field twice deep inside a document is refused`() {
        val nested = """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z","policies":[""" +
            """{"id":"p","statements":[{"effect":"allow","action":"*","action":"http:GET"}]}],"bindings":[]}"""
        assertThrows<ScenarioFormatException> { PolicyTestKit.of(nested) }.message.assert().contains("action")
    }

    @Test
    fun `a file with a field twice is refused naming the file`(@TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("platform.json"), duplicated)

        assertThrows<ScenarioFormatException> { PolicyTestKit.load(directory) }.message.assert()
            .contains(file.toString())
        assertThrows<ScenarioFormatException> { PipelineFixture.load(directory) }
    }

    @Test
    fun `a scenario with a field twice is refused`() {
        val kit = PolicyTestKit.of(platform)
        val twice = """{"credential":{"anonymous":true},"request":{"action":"http:GET"},"request":{"action":"http:POST"}}"""

        assertThrows<ScenarioFormatException> { kit.evaluate(twice) }.message.assert().contains("request")
        assertThrows<ScenarioFormatException> { kit.check("twice", twice) }
    }

    /** `%61` decodes to `a`: two spellings of acme. The first does not compile and must not vanish behind the second. */
    @Test
    fun `two files of one tenant are refused, naming both`(@TempDir directory: Path) {
        Files.createDirectories(directory.resolve("tenants"))
        Files.writeString(directory.resolve("tenants/%61cme.json"), """{"unknown":true}""")
        assertThrows<RuntimeException> { PolicyTestKit.load(directory) }

        Files.writeString(directory.resolve("tenants/acme.json"), "{}")

        val error = assertThrows<ScenarioFormatException> { PolicyTestKit.load(directory) }
        error.message.assert().contains("tenants/%61cme.json").contains("tenants/acme.json").contains("[acme]")
        assertThrows<ScenarioFormatException> { PipelineFixture.load(directory) }
    }

    @Test
    fun `two files of one app are refused, and a space spelled two ways too`(@TempDir directory: Path) {
        Files.createDirectories(directory.resolve("apps"))
        Files.writeString(directory.resolve("apps/my app.json"), "{}")
        Files.writeString(directory.resolve("apps/my%20app.json"), "{}")
        assertThrows<ScenarioFormatException> { PolicyTestKit.load(directory) }.message.assert()
            .contains("apps/my app.json").contains("apps/my%20app.json")
    }

    @Test
    fun `a shortened name the report maps to an id another file already holds is refused`(@TempDir directory: Path) {
        Files.createDirectories(directory.resolve("tenants"))
        Files.writeString(directory.resolve("tenants/long~abc.json"), "{}")
        Files.writeString(directory.resolve("tenants/acme.json"), "{}")
        Files.writeString(directory.resolve("report.json"), """{"documentIds":{"tenants/long~abc.json":"acme"}}""")

        assertThrows<ScenarioFormatException> { PolicyTestKit.load(directory) }.message.assert()
            .contains("tenants/acme.json").contains("tenants/long~abc.json")
    }

    @Test
    fun `distinct files and a tenant and an app of one name are fine`(@TempDir directory: Path) {
        Files.createDirectories(directory.resolve("tenants"))
        Files.createDirectories(directory.resolve("apps"))
        Files.writeString(directory.resolve("tenants/acme.json"), "{}")
        Files.writeString(directory.resolve("tenants/globex.json"), "{}")
        Files.writeString(directory.resolve("apps/acme.json"), "{}")

        PolicyTestKit.load(directory)
    }
}
