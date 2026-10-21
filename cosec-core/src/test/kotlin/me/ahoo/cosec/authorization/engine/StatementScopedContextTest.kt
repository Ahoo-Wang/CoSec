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

package me.ahoo.cosec.authorization.engine

import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.engine.PolicySet
import me.ahoo.cosec.model.Decision
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.model.StatementRef
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.action.PATH_VARIABLES_KEY
import me.ahoo.cosec.policy.action.clearPathVariables
import me.ahoo.cosec.policy.action.getPathVariables
import me.ahoo.cosec.policy.action.setPathVariables
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StatementScopedContextTest {
    private val base = SimpleSecurityContext(SimplePrincipal("user"))
    private val scoped = StatementScopedContext(base)

    @Test
    fun pathVariablesStayInTheStatement() {
        base.setPathVariables(mapOf("id" to "caller"))
        scoped.getPathVariables().assert().isEmpty()
        scoped.setPathVariables(mapOf("id" to "1"))
        scoped.getPathVariables().assert().isEqualTo(mapOf("id" to "1"))
        scoped.attributes[PATH_VARIABLES_KEY].assert().isEqualTo(mapOf("id" to "1"))
        base.getPathVariables().assert().isEqualTo(mapOf("id" to "caller"))
        scoped.beginStatement()
        scoped.getPathVariables().assert().isEmpty()
        scoped.clearPathVariables()
        scoped.getPathVariables().assert().isEmpty()
    }

    @Test
    fun otherAttributesAreTheCallers() {
        scoped.setAttributeValue("k", "v")
        base.getAttributeValue<String>("k").assert().isEqualTo("v")
        scoped.attributes["k2"] = "v2"
        base.attributes["k2"].assert().isEqualTo("v2")
        scoped.attributes.containsKey("k2").assert().isTrue()
        scoped.attributes.remove("k2").assert().isEqualTo("v2")
        base.attributes.containsKey("k2").assert().isFalse()
        scoped.principal.assert().isSameAs(base.principal)
        scoped.tenant.assert().isSameAs(base.tenant)
        scoped.toString().assert().isEqualTo(base.toString())
    }

    @Test
    fun theAttributesViewShowsTheStatementsPathVariables() {
        base.setAttributeValue("k", "v")
        base.setPathVariables(mapOf("id" to "caller"))
        scoped.setPathVariables(mapOf("id" to "1"))
        val attributes = scoped.attributes
        attributes.size.assert().isEqualTo(2)
        attributes.toMap().assert().isEqualTo(mapOf("k" to "v", PATH_VARIABLES_KEY to mapOf("id" to "1")))
        attributes.containsKey(PATH_VARIABLES_KEY).assert().isTrue()
        attributes.put(PATH_VARIABLES_KEY, mapOf("id" to "2")).assert().isEqualTo(mapOf("id" to "1"))
        val entry = attributes.entries.first { it.key == PATH_VARIABLES_KEY }
        entry.toString().assert().isEqualTo("$PATH_VARIABLES_KEY={id=2}")
        entry.setValue(mapOf("id" to "3")).assert().isEqualTo(mapOf("id" to "2"))
        (entry == java.util.AbstractMap.SimpleEntry<String, Any>(PATH_VARIABLES_KEY, mapOf("id" to "3"))).assert().isTrue()
        entry.hashCode().assert().isEqualTo(PATH_VARIABLES_KEY.hashCode() xor mapOf("id" to "3").hashCode())

        attributes.remove(PATH_VARIABLES_KEY).assert().isEqualTo(mapOf("id" to "3"))
        scoped.getPathVariables().assert().isNull()
        attributes.containsKey(PATH_VARIABLES_KEY).assert().isFalse()
        attributes.size.assert().isEqualTo(1)
        base.getPathVariables().assert().isEqualTo(mapOf("id" to "caller"))
    }

    @Test
    fun entriesCanBeRemovedWhileIterating() {
        base.setAttributeValue("k", "v")
        val iterator = scoped.attributes.entries.iterator()
        iterator.next().key.assert().isEqualTo("k")
        iterator.remove()
        iterator.next().key.assert().isEqualTo(PATH_VARIABLES_KEY)
        iterator.remove()
        iterator.hasNext().assert().isFalse()
        assertThrows<NoSuchElementException> { iterator.next() }
        base.attributes.containsKey("k").assert().isFalse()
        scoped.getPathVariables().assert().isNull()
        assertThrows<UnsupportedOperationException> {
            scoped.attributes.entries.add(java.util.AbstractMap.SimpleEntry("x", "y"))
        }
    }

    @Test
    fun resourceAttributesAreTheResourcePrefixedRequestAttributes() {
        val request = LegacyFacts.authzRequest(
            EvaluateRequest(attributes = mapOf("resource.ownerId" to "u1", "routeId" to "r")),
            base,
        )
        request.resource.attributes.assert().isEqualTo(mapOf("ownerId" to "u1"))
        LegacyFacts.authzRequest(EvaluateRequest(), base).resource.attributes.assert().isEmpty()
    }

    @Test
    fun aDecisionTheEvaluationDidNotMatchFailsClosed() {
        val facts = LegacyFacts(EvaluateRequest(), base)
        assertThrows<IllegalStateException> {
            Decisions.of(
                Decision.Permit(StatementRef.PolicyStatement(LayerKind.GLOBAL, "p", 0, "s")),
                PolicySet.NOT_LOADED,
                facts
            )
        }
        assertThrows<IllegalStateException> {
            Decisions.of(Decision.Deny(StatementRef.RolePermission("app", "r", "p", "n")), PolicySet.NOT_LOADED, facts)
        }
        assertThrows<IllegalStateException> {
            Decisions.of(
                Decision.Permit(StatementRef.Blacklist),
                PolicySet.NOT_LOADED,
                null
            )
        }
        assertThrows<IllegalStateException> {
            Decisions.of(
                Decision.Deny(StatementRef.Root),
                PolicySet.NOT_LOADED,
                null
            )
        }
        assertThrows<IllegalArgumentException> {
            Decisions.of(
                Decision.Indeterminate(
                    me.ahoo.cosec.model.Cause.SourceUnavailable(LayerKind.GLOBAL, IllegalStateException())
                ),
                PolicySet.NOT_LOADED,
                null
            )
        }
    }
}
