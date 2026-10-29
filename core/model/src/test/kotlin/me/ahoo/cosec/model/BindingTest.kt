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

    /** Documents write `*` for Match.Any, so no concrete id may be `*` (RFC 0004 §4.1). */
    @Test
    fun `the wildcard is not an app or space id`() {
        runCatching { AppId(Match.WILDCARD) }.isFailure.assert().isTrue()
        runCatching { SpaceId(Match.WILDCARD) }.isFailure.assert().isTrue()
        runCatching { Match.Exactly(Match.WILDCARD) }.isFailure.assert().isTrue()
        listOf(
            AppId("**").value,
            SpaceId(" *").value,
            Match.Exactly("a*").value
        ).assert().containsExactly("**", " *", "a*")
    }

    @Test
    fun `any matches every value and exactly matches only its own`() {
        val anyApp: Match<AppId> = Match.Any
        val order: Match<AppId> = Match.Exactly(AppId("order"))
        val teamA: Match<SpaceId> = Match.Exactly(SpaceId("team-a"))
        val anySpace: Match<SpaceId> = Match.Any
        for (app in listOf("order", "ORDER", " order", "", "billing")) {
            anyApp.matches(AppId(app)).assert().isTrue()
            order.matches(AppId(app)).assert().describedAs(app).isEqualTo(app == "order")
        }
        for (space in listOf("team-a", "TEAM-A", "team-a ", "")) {
            anySpace.matches(SpaceId(space)).assert().isTrue()
            teamA.matches(SpaceId(space)).assert().describedAs(space).isEqualTo(space == "team-a")
        }
        val role: Match<RoleId> = Match.Exactly(RoleId("admin"))
        role.matches(RoleId("admin")).assert().isTrue()
        role.matches(RoleId("Admin")).assert().isFalse()
        val anyRole: Match<RoleId> = Match.Any
        anyRole.matches(RoleId("x")).assert().isTrue()
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
