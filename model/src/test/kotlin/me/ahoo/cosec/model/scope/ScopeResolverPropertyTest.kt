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

package me.ahoo.cosec.model.scope

import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Property P11 of RFC 0004 §9.5, on random subjects, declarations and hosts with a fixed seed. */
class ScopeResolverPropertyTest {
    private val iterations = 2_000

    // Near misses of real values (case, whitespace, separators) catch a resolver that normalises before comparing.
    private val spaces = listOf("", "team-a", "TEAM-A", " team-a", "team-a ", "team-b", "a@b", "a:b", " ")
    private val declaredSpaces = spaces + listOf("*", "team-c")
    private val hostApps = listOf("order", "billing", "a@b")
    private val apps = listOf("", "order", "ORDER", " order", "billing", "*", "a:b", "a@b", " ", "x".repeat(129))
    private val roles = listOf("admin", "editor", "viewer")

    private class Case(val subject: ScopeSubject, val declared: DeclaredScope, val host: HostScope) {
        fun describe(): String {
            val roles = subject.memberships.roles.map { (space, roles) -> space.value to roles.map { it.value } }
            return "tenant=${subject.tenant.value} authenticated=${subject.authenticated} roles=$roles " +
                "declared=(${declared.app}, ${declared.space}) host=${host.app?.value}"
        }
    }

    private fun Random.case(): Case {
        val memberships = spaces.filter { nextBoolean() }.associate { space ->
            SpaceId(space) to roles.filter { nextInt(3) == 0 }.map(::RoleId).toCollection(LinkedHashSet())
        }
        val subject = ScopeSubject(
            TenantId(listOf("acme", "(platform)", "(0)").random(this)),
            nextBoolean(),
            Memberships(Memberships.Source.entries.random(this), memberships, emptyList()),
        )
        val host = HostScope(if (nextBoolean()) null else AppId(hostApps.random(this)))
        return Case(subject, DeclaredScope(apps.random(this), declaredSpaces.random(this)), host)
    }

    private fun forEachCase(seed: Int, block: (Case, ScopeResolution) -> Unit) {
        val random = Random(seed)
        repeat(iterations) { iteration ->
            val case = random.case()
            try {
                block(case, ScopeResolver.resolve(case.subject, case.declared, case.host))
            } catch (e: AssertionError) {
                throw AssertionError("Case $iteration (seed $seed): ${case.describe()}", e)
            }
        }
    }

    /** P11: a scope only narrows; a declared space outside the memberships is always rejected. */
    @Test
    fun `P11 a resolved scope only narrows`() {
        forEachCase(seed = 20261025) { case, resolution ->
            val granted = if (case.subject.authenticated) case.subject.memberships.roles else emptyMap()
            val member = case.declared.space != Match.WILDCARD &&
                granted[SpaceId(case.declared.space)].orEmpty().isNotEmpty()
            if (case.declared.space.isNotEmpty() && !member) {
                resolution.assert().isInstanceOf(ScopeResolution.Rejected::class.java)
            }
            if (resolution is ScopeResolution.Resolved) {
                val scope = resolution.scope
                granted[scope.space].orEmpty().containsAll(scope.roles).assert().isTrue()
                scope.space.value.assert().isEqualTo(case.declared.space)
            }
        }
    }

    /** The host app wins over the declared one, malformed apps are rejected, and the tenant comes from the credential. */
    @Test
    fun `the app and tenant follow the host and the credential`() {
        forEachCase(seed = 7) { case, resolution ->
            val hostApp = case.host.app
            val declared = case.declared.app
            val invalid = declared.isNotEmpty() &&
                (declared.isBlank() || declared == "*" || ':' in declared || declared.length > 128)
            val mismatch = invalid || hostApp != null && declared.isNotEmpty() && declared != hostApp.value
            if (mismatch) {
                (resolution as ScopeResolution.Rejected).reason.assert()
                    .isEqualTo(ScopeResolution.Rejected.Reason.APP_MISMATCH)
            }
            if (resolution is ScopeResolution.Resolved) {
                val scope = resolution.scope
                scope.app.assert().isEqualTo(hostApp ?: AppId(case.declared.app))
                scope.tenant.assert()
                    .isEqualTo(if (case.subject.authenticated) case.subject.tenant else TenantId.DEFAULT)
            }
        }
    }
}
