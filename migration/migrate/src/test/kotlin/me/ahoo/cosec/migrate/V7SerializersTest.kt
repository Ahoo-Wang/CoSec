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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.migrate.v7.api.permission.PermissionGroup
import me.ahoo.cosec.migrate.v7.api.policy.Policy
import me.ahoo.cosec.migrate.v7.api.policy.PolicyType
import me.ahoo.cosec.migrate.v7.serialization.V7JsonSerializer
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/**
 * The 7.x reader of policies and permission groups: what it accepts must not move. Each text field is read as a string,
 * an empty string, `null` and a number (what Jackson's `asText`, now `asString`, has always answered for each), and a
 * required one that is absent is refused, naming the key.
 */
class V7SerializersTest {
    /** What the reader answers for a field whose JSON is [json]: the text, or `null` when it is absent. */
    private val values = listOf(
        """"x"""" to "x",
        """""""" to "",
        "null" to "",
        "5" to "5",
        "true" to "true",
    )

    private fun jsonObject(fields: Map<String, String>): String =
        fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }

    private fun policy(field: String, json: String?): Policy {
        val fields = linkedMapOf(
            "id" to "\"p\"",
            "name" to "\"n\"",
            "category" to "\"c\"",
            "description" to "\"d\"",
            "type" to "\"CUSTOM\"",
            "tenantId" to "\"t\"",
        )
        if (json == null) fields.remove(field) else fields[field] = json
        return V7JsonSerializer.readValue(jsonObject(fields), Policy::class.java)
    }

    private fun group(field: String, json: String?): PermissionGroup {
        val fields = linkedMapOf("name" to "\"g\"", "description" to "\"d\"", "permissions" to "[]")
        if (json == null) fields.remove(field) else fields[field] = json
        return V7JsonSerializer.readValue(jsonObject(fields), PermissionGroup::class.java)
    }

    @Test
    fun `a policy reads each text field as the text it holds`() {
        for ((json, text) in values) {
            policy("id", json).id.assert().isEqualTo(text)
            policy("name", json).name.assert().isEqualTo(text)
            policy("tenantId", json).tenantId.assert().isEqualTo(text)
            policy("category", json).category.assert().isEqualTo(text)
            policy("description", json).description.assert().isEqualTo(text)
        }
        policy("type", """"GLOBAL"""").type.assert().isEqualTo(PolicyType.GLOBAL)
    }

    @Test
    fun `a policy without a category or a description has empty ones, and without an id, name, type or tenant is refused`() {
        policy("category", null).category.assert().isEmpty()
        policy("description", null).description.assert().isEmpty()
        for (required in listOf("id", "name", "type", "tenantId")) {
            assertThrownBy<Exception> { policy(required, null) }.hasMessageContaining(required)
        }
    }

    @Test
    fun `a permission group reads its name and description as the text they hold`() {
        for ((json, text) in values) {
            group("name", json).name.assert().isEqualTo(text)
            group("description", json).description.assert().isEqualTo(text)
        }
        group("permissions", "[]").permissions.assert().isEmpty()
    }

    @Test
    fun `a permission group without a description has an empty one, and without a name is refused`() {
        group("description", null).description.assert().isEmpty()
        assertThrownBy<Exception> { group("name", null) }.hasMessageContaining("name")
    }
}
