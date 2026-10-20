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

package me.ahoo.cosec.authorization

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class RoleScopeTest {

    @Test
    fun defaultSpaceKeepsUnsuffixedRolesWithoutCopying() {
        val roles = setOf("admin", "viewer")
        RoleScope.rolesIn("", roles).assert().isSameAs(roles)
    }

    @Test
    fun defaultSpaceDropsSpacedRoles() {
        RoleScope.rolesIn("", setOf("admin", "admin@team-a", "viewer@team-b")).assert().containsExactly("admin")
    }

    @Test
    fun spaceKeepsOnlyItsOwnRolesStripped() {
        RoleScope.rolesIn("team-a", setOf("admin", "admin@team-a", "viewer@team-b", "auditor@team-a"))
            .assert().containsExactly("admin", "auditor")
    }

    @Test
    fun empty() {
        RoleScope.rolesIn("team-a", emptySet()).assert().isEmpty()
        RoleScope.rolesIn("team-a", setOf("admin")).assert().isEmpty()
    }

    @ParameterizedTest
    @CsvSource(
        "'', admin, admin",
        "'', admin@team-a, ",
        "'', '', ",
        "'', admin@, ",
        "team-a, admin@team-a, admin",
        "team-a, admin, ",
        "team-a, admin@team-ab, ",
        "team-a, admin@team, ",
        "team-a, @team-a, ",
        "team-a, admin@team-a@x, ",
        "team-a@x, admin@team-a@x, ",
        "a:b, admin@a:b, admin",
    )
    fun roleIdIn(spaceId: String, role: String, expected: String?) {
        RoleScope.roleIdIn(spaceId, role).assert().isEqualTo(expected)
    }
}
