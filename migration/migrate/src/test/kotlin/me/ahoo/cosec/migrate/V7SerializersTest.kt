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
 * The 7.x reader of policies and permission groups: what it accepts must not move (its text fields are read with
 * `asString`, Jackson 3's name for what `asText` answered: a missing, a null and a text field alike).
 */
class V7SerializersTest {
    @Test
    fun `a policy reads every text field`() {
        val policy = V7JsonSerializer.readValue(
            """{"id":"p","name":"n","category":"c","description":"d","type":"CUSTOM","tenantId":"t"}""",
            Policy::class.java,
        )
        policy.id.assert().isEqualTo("p")
        policy.name.assert().isEqualTo("n")
        policy.category.assert().isEqualTo("c")
        policy.description.assert().isEqualTo("d")
        policy.type.assert().isEqualTo(PolicyType.CUSTOM)
        policy.tenantId.assert().isEqualTo("t")
    }

    @Test
    fun `a policy without a category or a description, or with null ones, has empty ones`() {
        for (optional in listOf("", ""","category":null,"description":null""")) {
            val policy = V7JsonSerializer.readValue(
                """{"id":"p","name":"n","type":"GLOBAL","tenantId":"t"$optional}""",
                Policy::class.java,
            )
            policy.category.assert().isEmpty()
            policy.description.assert().isEmpty()
        }
    }

    @Test
    fun `a policy without its id, name, type or tenant is refused, naming the key`() {
        val whole = mapOf("id" to "\"p\"", "name" to "\"n\"", "type" to "\"SYSTEM\"", "tenantId" to "\"t\"")
        for (missing in whole.keys) {
            val json = whole.filterKeys { it != missing }.entries.joinToString(
                ",",
                "{",
                "}"
            ) { "\"${it.key}\":${it.value}" }
            assertThrownBy<Exception> { V7JsonSerializer.readValue(json, Policy::class.java) }
                .hasMessageContaining(missing)
        }
    }

    @Test
    fun `a permission group reads its name and description, and a missing or null description is empty`() {
        val group = V7JsonSerializer.readValue(
            """{"name":"g","description":"d","permissions":[]}""",
            PermissionGroup::class.java,
        )
        group.name.assert().isEqualTo("g")
        group.description.assert().isEqualTo("d")
        group.permissions.assert().isEmpty()
        for (description in listOf("", ""","description":null""")) {
            V7JsonSerializer.readValue("""{"name":"g"$description}""", PermissionGroup::class.java)
                .description.assert().isEmpty()
        }
    }

    @Test
    fun `a permission group without a name is refused`() {
        assertThrownBy<Exception> { V7JsonSerializer.readValue("""{"description":"d"}""", PermissionGroup::class.java) }
            .hasMessageContaining("name")
    }
}
