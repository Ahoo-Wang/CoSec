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

package me.ahoo.cosec.policy.action

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.permission.Permission
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.limiter.RateLimitRule
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class ReplaceablePathActionMatcherTest {

    @Test
    fun match() {
        val securityContext = SimpleSecurityContext(
            principal = mockk {
                every { id } returns "1"
            }
        )

        val actionMatcher =
            PathActionMatcherFactory().create("order/#{principal.id}/*".asConfiguration())
        assertThat(actionMatcher, instanceOf(ReplaceablePathActionMatcher::class.java))
        val request1 = mockk<Request> {
            every {
                path
            } returns "order/1/1"
        }
        assertThat(actionMatcher.match(request1, securityContext), equalTo(true))
        val request2 = mockk<Request> {
            every {
                path
            } returns "order/1/1/hi"
        }
        assertThat(actionMatcher.match(request2, securityContext), equalTo(false))
    }

    @Test
    fun matchWithCustomSeparator() {
        val securityContext = SimpleSecurityContext(
            principal = mockk {
                every { id } returns "1"
            }
        )
        val configuration = mapOf(
            "pattern" to "order.#{principal.id}.*",
            "options" to mapOf("separator" to "."),
        ).asConfiguration()
        val actionMatcher = PathActionMatcherFactory().create(configuration)
        assertThat(actionMatcher, instanceOf(ReplaceablePathActionMatcher::class.java))
        val matchedRequest = mockk<Request> {
            every { path } returns "order.1.1"
        }
        assertThat(actionMatcher.match(matchedRequest, securityContext), equalTo(true))
        val unmatchedRequest = mockk<Request> {
            every { path } returns "order.2.1"
        }
        assertThat(actionMatcher.match(unmatchedRequest, securityContext), equalTo(false))
    }

    @Test
    fun templateDoesNotSeeAnotherStatementsPathVariables() {
        val securityContext = SimpleSecurityContext(principal = mockk())
        // Left behind by the previous statement's path action.
        securityContext.setPathVariables(mapOf("id" to "1"))
        // Built at run time, so the load-time check cannot reject it.
        val actionMatcher =
            PathActionMatcherFactory().create("/o/#{attributes['PATH_' + 'VARIABLES']['id']}".asConfiguration())
                .onRejectedTemplateValue(RejectedTemplateValue.NO_MATCH)
        assertThat(actionMatcher, instanceOf(ReplaceablePathActionMatcher::class.java))
        val request = mockk<Request> {
            every { path } returns "/o/1"
        }
        assertThat(actionMatcher.match(request, securityContext), equalTo(false))
    }

    private fun context(id: String) = SimpleSecurityContext(SimplePrincipal(id))

    private fun request(path: String) = mockk<Request> {
        every { this@mockk.path } returns path
        every { method } returns "GET"
    }

    private fun templated(pattern: String, onRejected: RejectedTemplateValue) =
        (PathActionMatcherFactory.INSTANCE.create(pattern.asConfiguration()) as ReplaceablePathActionMatcher)
            .onRejectedTemplateValue(onRejected)

    @Test
    fun rejectedValuesFollowTheMatchersChoice() {
        val pattern = "/users/#{principal.id}/**"
        listOf("*", "{x}", "?", "a/b", "", ".", "..", "}").forEach { id ->
            templated(pattern, RejectedTemplateValue.NO_MATCH).match(request("/users/victim/x"), context(id))
                .assert().describedAs("id [$id]").isFalse()
            templated(pattern, RejectedTemplateValue.MATCH).match(request("/anything"), context(id))
                .assert().describedAs("id [$id]").isTrue()
            assertThrows<RejectedTemplateValueException> {
                templated(pattern, RejectedTemplateValue.ERROR).match(request("/users/victim/x"), context(id))
            }
        }
    }

    @Test
    fun aMatcherBuiltWithoutAnEffectFailsClosedWithAnError() {
        val matcher = PathActionMatcherFactory.INSTANCE.create("/users/#{principal.id}/**".asConfiguration())
        (matcher as ReplaceablePathActionMatcher).onRejected.assert().isEqualTo(RejectedTemplateValue.ERROR)
        assertThrows<RejectedTemplateValueException> { matcher.match(request("/users/victim/x"), context("evil*")) }
            .message.assert().doesNotContain("evil")
    }

    @Test
    fun acceptedValuesStillMatch() {
        val matcher = templated("/users/#{principal.id}/**", RejectedTemplateValue.NO_MATCH)
        matcher.match(request("/users/alice/orders"), context("alice")).assert().isTrue()
        matcher.match(request("/users/victim/orders"), context("alice")).assert().isFalse()
        // Not pattern syntax: a literal segment like any other.
        matcher.match(request("/users/a@github.com/orders"), context("a@github.com")).assert().isTrue()
    }

    @Test
    fun staticPartsStayPatternSyntax() {
        val matcher = templated("/orgs/{org}/users/#{principal.id}/*", RejectedTemplateValue.NO_MATCH)
        val securityContext = context("alice")
        matcher.match(request("/orgs/acme/users/alice/1"), securityContext).assert().isTrue()
        securityContext.getPathVariables().assert().isEqualTo(mapOf("org" to "acme"))
        matcher.match(request("/orgs/acme/users/bob/1"), context("alice")).assert().isFalse()
    }

    @Test
    fun aHoleInsideACaptureIsRejectedWhenBuilt() {
        assertThrows<IllegalArgumentException> {
            PathActionMatcherFactory.INSTANCE.create("/files/{name:#{principal.attributes['ext']}}".asConfiguration())
        }
        assertThrows<IllegalArgumentException> {
            PathActionMatcherFactory.INSTANCE.create("/files/{#{principal.id}}".asConfiguration())
        }
    }

    @Test
    fun aTemplateMadeOfOneHoleOnlyFillsOneSegment() {
        val matcher = templated("#{principal.id}", RejectedTemplateValue.NO_MATCH)
        matcher.match(request("alice"), context("alice")).assert().isTrue()
        matcher.match(request("/users/alice"), context("/users/alice")).assert().isFalse()
    }

    @Test
    fun theSeparatorOfTheParserIsRejected() {
        val configuration = mapOf(
            "pattern" to "order.#{principal.id}.*",
            "options" to mapOf("separator" to "."),
        ).asConfiguration()
        val matcher = (PathActionMatcherFactory.INSTANCE.create(configuration) as ReplaceablePathActionMatcher)
            .onRejectedTemplateValue(RejectedTemplateValue.NO_MATCH)
        matcher.match(request("order.a.b.1"), context("a.b")).assert().isFalse()
        matcher.match(request("order.a/b.1"), context("a/b")).assert().isTrue()
    }

    @Test
    fun compositeMembersFollowTheChoice() {
        val members = listOf("/a/#{principal.id}", "/b/#{principal.id}", "/c")
            .map { PathActionMatcherFactory.INSTANCE.create(it.asConfiguration()) }
        val composite = CompositeActionMatcher(PathActionMatcherFactory.TYPE, members, "".asConfiguration())
        val denying = composite.onRejectedTemplateValue(RejectedTemplateValue.MATCH) as CompositeActionMatcher
        denying.actionMatchers.filterIsInstance<ReplaceablePathActionMatcher>().map { it.onRejected }.assert()
            .containsExactly(RejectedTemplateValue.MATCH, RejectedTemplateValue.MATCH)
        denying.actionMatchers[2].assert().isSameAs(composite.actionMatchers[2])
        val static = CompositeActionMatcher(
            PathActionMatcherFactory.TYPE,
            listOf(PathActionMatcherFactory.INSTANCE.create("/a".asConfiguration())),
            "".asConfiguration(),
        )
        static.onRejectedTemplateValue(RejectedTemplateValue.MATCH).assert().isSameAs(static)
    }

    @Test
    fun deserializedStatementsPermissionsAndRateLimitRulesChooseByEffect() {
        fun Statement.onRejected() = (action as ReplaceablePathActionMatcher).onRejected
        CoSecJsonSerializer.readValue("""{"action":"/u/#{principal.id}"}""", Statement::class.java)
            .onRejected().assert().isEqualTo(RejectedTemplateValue.NO_MATCH)
        CoSecJsonSerializer.readValue("""{"effect":"deny","action":"/u/#{principal.id}"}""", Statement::class.java)
            .onRejected().assert().isEqualTo(RejectedTemplateValue.MATCH)
        CoSecJsonSerializer.readValue(
            """{"id":"p","effect":"deny","action":"/u/#{principal.id}"}""",
            Permission::class.java
        ).onRejected().assert().isEqualTo(RejectedTemplateValue.MATCH)
        CoSecJsonSerializer.readValue(
            """{"id":"p","action":"/u/#{principal.id}"}""",
            Permission::class.java
        ).onRejected().assert().isEqualTo(RejectedTemplateValue.NO_MATCH)
        val rule = CoSecJsonSerializer.readValue(
            """{"id":"own","action":"/users/#{principal.id}/**","permitsPerSecond":1}""",
            RateLimitRule::class.java,
        )
        (rule.action as ReplaceablePathActionMatcher).onRejected.assert().isEqualTo(RejectedTemplateValue.MATCH)
        // A principal whose id is rejected cannot escape the limit.
        rule.matches(request("/users/victim/x"), context("*")).assert().isTrue()
        rule.matches(request("/users/victim/x"), context("alice")).assert().isFalse()
        rule.matches(request("/users/alice/x"), context("alice")).assert().isTrue()
    }
}
