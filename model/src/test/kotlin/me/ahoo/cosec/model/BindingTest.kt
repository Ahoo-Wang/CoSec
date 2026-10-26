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

package me.ahoo.cosec.model

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class BindingTest {
    @Test
    fun `well-known ids match their 7x constants`() {
        TenantId.PLATFORM.value.assert().isEqualTo("(platform)")
        TenantId.DEFAULT.value.assert().isEqualTo("(0)")
        SpaceId.DEFAULT.value.assert().isEmpty()
        listOf(PrincipalId("u").value, AppId("a").value, RoleId("r").value, PolicyId("p").value, BindingId("b").value)
            .assert().containsExactly("u", "a", "r", "p", "b")
    }

    @Test
    fun `a binding keeps what it is given`() {
        val subject = SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("cosec"))
        val scope = BindingScope(Match.Exactly(AppId("order")), Match.Any)
        val binding = Binding(
            BindingId("root"),
            subject,
            scope,
            PolicyId("cosec:root"),
            expiresAt = 1L,
            breakGlass = true
        )

        binding.id.assert().isEqualTo(BindingId("root"))
        binding.subject.assert().isEqualTo(SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("cosec")))
        binding.scope.app.assert().isEqualTo(Match.Exactly(AppId("order")))
        binding.scope.space.assert().isSameAs(Match.Any)
        binding.policy.assert().isEqualTo(PolicyId("cosec:root"))
        binding.expiresAt.assert().isEqualTo(1L)
        binding.breakGlass.assert().isTrue()
    }

    @Test
    fun `a binding neither expires nor breaks glass by default`() {
        val binding = Binding(
            BindingId("g:p"),
            SubjectRef.Anyone,
            BindingScope(Match.Any, Match.Any),
            PolicyId("p"),
        )
        binding.expiresAt.assert().isNull()
        binding.breakGlass.assert().isFalse()
    }

    @Test
    fun `subject references compare by value`() {
        SubjectRef.Role(null, RoleId("admin")).assert().isEqualTo(SubjectRef.Role(null, RoleId("admin")))
        SubjectRef.Role(TenantId("acme"), RoleId("admin")).assert()
            .isNotEqualTo(SubjectRef.Role(null, RoleId("admin")))
        SubjectRef.Principal(TenantId("a"), PrincipalId("u")).assert()
            .isNotEqualTo(SubjectRef.Principal(TenantId("b"), PrincipalId("u")))
    }

    @Test
    fun `memberships keep claim order and have no malformed entries by default`() {
        val roles = linkedMapOf(
            SpaceId.DEFAULT to linkedSetOf(RoleId("b"), RoleId("a")),
            SpaceId("team-a") to linkedSetOf(RoleId("editor")),
        )
        val memberships = Memberships(Memberships.Source.CLAIMS, roles, listOf(PolicyId("p2"), PolicyId("p1")))

        memberships.source.assert().isEqualTo(Memberships.Source.CLAIMS)
        memberships.roles.getValue(SpaceId.DEFAULT).toList().assert().containsExactly(RoleId("b"), RoleId("a"))
        memberships.attachments.assert().containsExactly(PolicyId("p2"), PolicyId("p1"))
        memberships.malformed.assert().isEmpty()
        Memberships(Memberships.Source.PROVIDER, emptyMap(), emptyList(), listOf("a@b@c")).malformed.assert()
            .containsExactly("a@b@c")
    }
}
