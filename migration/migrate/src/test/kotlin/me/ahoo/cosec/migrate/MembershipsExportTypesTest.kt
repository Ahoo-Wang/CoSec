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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.TenantId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * #1546: a `--memberships` entry whose `roles` is not an array of strings used to read as "no roles", which moves an
 * API key to another identity. A wrong type is refused, naming the entry and the field; `[]` stays "no roles".
 */
class MembershipsExportTypesTest {
    private fun read(entry: String) = MembershipsExport.read("[$entry]".encodeToByteArray())

    @ParameterizedTest
    @ValueSource(
        strings = ["\"reader\"", "3", "true", "null", "{}", "{\"a\":\"reader\"}", "[1]", "[null]", "[[\"r\"]]", "[{}]"]
    )
    fun `roles that is not an array of strings is refused, naming the entry and the field`(roles: String) {
        val error = assertThrows<IllegalArgumentException> {
            read("""{"tenant":"acme","principal":"svc","roles":$roles}""")
        }
        error.message.assert().contains("svc@acme").contains("roles")
    }

    @ParameterizedTest
    @ValueSource(strings = ["3", "true", "null", "{}", "[]"])
    fun `tenant and principal that are not strings are refused, naming the entry and the field`(value: String) {
        assertThrows<IllegalArgumentException> { read("""{"tenant":$value,"principal":"svc","roles":[]}""") }
            .message.assert().contains("entry 0").contains("tenant")
        assertThrows<IllegalArgumentException> { read("""{"tenant":"acme","principal":$value,"roles":[]}""") }
            .message.assert().contains("entry 0").contains("principal")
    }

    /** `IDN-38`: an entry of no one is refused, naming the entry, never read as a principal. */
    @Test
    fun `a blank principal is refused, naming the entry`() {
        for (blank in listOf("", " ", "\\t")) {
            assertThrows<IllegalArgumentException> { read("""{"tenant":"acme","principal":"$blank","roles":[]}""") }
                .message.assert().contains("entry 0").contains("principal is blank")
        }
    }

    /** `MIG-6`: the optional attachments are read as roles are, and nothing else may stand beside them. */
    @Test
    fun `attachments are an optional array of strings, and no other member is read`() {
        val json = """[{"tenant":"acme","principal":"svc","roles":[],"attachments":["deny-x"]},""" +
            """{"tenant":"acme","principal":"other","roles":["r"]}]"""
        MembershipsExport.attachments(json.encodeToByteArray())
            .assert().isEqualTo(mapOf((TenantId("acme") to "svc") to setOf("deny-x")))
        MembershipsExport.read(json.encodeToByteArray()).keys.assert().hasSize(2)
        listOf(
            """{"tenant":"acme","principal":"svc","roles":[],"attachments":"deny-x"}""",
            """{"tenant":"acme","principal":"svc","roles":[],"attachments":[1]}""",
            """{"tenant":"acme","principal":"svc","roles":[],"extra":[]}""",
            """{"tenant":"acme","principal":"svc"}""",
            "[]",
        ).forEach { entry ->
            assertThrows<IllegalArgumentException> { read(entry) }
        }
    }

    @Test
    fun `a real empty array still means no roles`() {
        read("""{"tenant":"acme","principal":"svc","roles":[]}""")
            .assert().isEqualTo(mapOf((TenantId("acme") to "svc") to emptySet<String>()))
    }
}
