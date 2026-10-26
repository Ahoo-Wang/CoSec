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

package me.ahoo.cosec.serialization

import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.policy.ActionMatcher
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.limiter.RateLimitRule
import me.ahoo.cosec.policy.StatementData
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.module.SimpleModule

/**
 * A policy document the author got wrong fails to decode instead of compiling to something that matches more than
 * was written.
 */
class StrictPolicyDecodingTest {
    private fun policyJson(statements: String, extra: String = ""): String =
        """{"id":"p","name":"p","type":"global","tenantId":"t",$extra"statements":[$statements]}"""

    private fun malformedPolicy(json: String): MalformedPolicyException =
        assertThrows<MalformedPolicyException> { CoSecJsonSerializer.readValue(json, Policy::class.java) }

    private fun malformedStatement(statement: String): MalformedPolicyException = malformedPolicy(policyJson(statement))

    @Test
    fun aMisspelledStatementFieldFails() {
        // Was an unconditional ALLOW: the misspelled key was ignored.
        val error = malformedStatement("""{"action":"*","conditon":{"inRole":{"value":"admin"}}}""")
        error.path.assert().isEqualTo("$.statements[0]")
        error.message.assert().contains("unknown field [conditon]")
    }

    @Test
    fun aConditionWithASecondKeyFails() {
        // Was `all`: only the first key was read.
        val error = malformedStatement("""{"action":"*","condition":{"all":{},"inRole":{"value":"admin"}}}""")
        error.path.assert().isEqualTo("$.statements[0].condition")
        error.message.assert().contains("exactly one key").contains("[all, inRole]")
    }

    @Test
    fun aScalarAndFails() {
        // Was an empty `and`, which matches everything.
        val error = malformedStatement("""{"action":"*","condition":{"bool":{"and":"inRole"}}}""")
        error.path.assert().isEqualTo("$.statements[0].condition.bool.and")
        error.message.assert().contains("expected an array")
    }

    @Test
    fun aScalarOrFails() {
        val error = malformedStatement("""{"action":"*","condition":{"bool":{"or":{"inRole":{"value":"admin"}}}}}""")
        error.path.assert().isEqualTo("$.statements[0].condition.bool.or")
    }

    @Test
    fun aConditionWithASecondKeyInsideOrFails() {
        val error = malformedStatement(
            """{"action":"*","condition":{"bool":{"or":[{"authenticated":{}},""" +
                """{"all":{},"inRole":{"value":"admin"}}]}}}""",
        )
        error.path.assert().isEqualTo("$.statements[0].condition.bool.or[1]")
    }

    @Test
    fun aNestedScalarAndFails() {
        val error = malformedStatement(
            """{"action":"*","condition":{"bool":{"and":[{"bool":{"and":"inRole"}}]}}}""",
        )
        error.path.assert().isEqualTo("$.statements[0].condition.bool.and[0].bool.and")
    }

    @Test
    fun anActionWithASecondKeyFails() {
        // Was `all`: only the first key was read.
        val error = malformedStatement("""{"effect":"deny","action":{"all":{},"path":"/admin/**"}}""")
        error.path.assert().isEqualTo("$.statements[0].action")
        error.message.assert().contains("[all, path]")
    }

    @Test
    fun anActionWithASecondKeyInsideAnArrayFails() {
        val error = malformedStatement("""{"action":["/a/**",{"path":"/b/**","all":{}}]}""")
        error.path.assert().isEqualTo("$.statements[0].action[1]")
    }

    @Test
    fun anActionWithASecondKeyInsideCompositeFails() {
        val error = malformedStatement("""{"action":{"composite":["/a/**",{"path":"/b/**","all":{}}]}}""")
        error.path.assert().isEqualTo("$.statements[0].action.composite[1]")
    }

    @Test
    fun anEmptyMatcherObjectFails() {
        malformedStatement("""{"action":{}}""").path.assert().isEqualTo("$.statements[0].action")
        malformedStatement("""{"action":"*","condition":{}}""").path.assert().isEqualTo("$.statements[0].condition")
    }

    @Test
    fun aMatcherThatIsNotAnObjectFails() {
        malformedStatement("""{"action":42}""").path.assert().isEqualTo("$.statements[0].action")
        malformedStatement("""{"action":"*","condition":"inRole"}""").path.assert()
            .isEqualTo("$.statements[0].condition")
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        textBlock = """
        {"action":"*","condition":{"in":{"part":"request.method","value":"GET"}}}                | $.statements[0].condition.in.value
        {"action":"*","condition":{"time":{"daysOfWeek":"MON"}}}                                  | $.statements[0].condition.time.daysOfWeek
        {"action":{"path":{"pattern":{"a":"/a"}}}}                                                | $.statements[0].action.path.pattern
        {"action":{"path":{"pattern":"/a","method":{"a":"GET"}}}}                                 | $.statements[0].action.path.method
        {"action":{"all":{"method":{"a":"GET"}}}}                                                 | $.statements[0].action.all.method
        {"action":{"composite":"/a/**"}}                                                          | $.statements[0].action.composite""",
    )
    fun otherListFieldsRejectScalars(statement: String, path: String) {
        malformedStatement(statement).path.assert().isEqualTo(path)
    }

    @Test
    fun anUnknownPolicyFieldFails() {
        val error = malformedPolicy("""{"id":"p","name":"p","type":"global","tenantId":"t","statement":[]}""")
        error.path.assert().isEqualTo("$")
        error.message.assert().contains("unknown field [statement] in a policy")
    }

    @Test
    fun aMalformedPolicyConditionFails() {
        malformedPolicy(policyJson("""{"action":"*"}""", """"condition":{"all":{},"authenticated":{}},"""))
            .path.assert().isEqualTo("$.condition")
    }

    @Test
    fun statementsThatAreNotAnArrayFail() {
        malformedPolicy("""{"id":"p","name":"p","type":"global","tenantId":"t","statements":{"action":"*"}}""")
            .path.assert().isEqualTo("$.statements")
    }

    @Test
    fun aDuplicateKeyFails() {
        assertThrows<JacksonException> {
            CoSecJsonSerializer.readValue(
                policyJson("""{"effect":"deny","action":"/admin/**","effect":"allow"}"""),
                Policy::class.java,
            )
        }
    }

    @Test
    fun documentedFieldsAndSchemaAreAccepted() {
        val policy = CoSecJsonSerializer.readValue(
            """{"${'$'}schema":"https://example.com/cosec-policy.schema.json","id":"p","name":"p","category":"c",""" +
                """"description":"d","type":"global","tenantId":"t","condition":{"authenticated":{}},""" +
                """"statements":[{"name":"s","effect":"deny","action":"*","condition":{"bool":{"and":[],"or":[]}}}]}""",
            Policy::class.java,
        )
        policy.statements.single().effect.assert().isEqualTo(Effect.DENY)
    }

    @Test
    fun aMisspelledPermissionFieldFails() {
        val error = assertThrows<MalformedPolicyException> {
            CoSecJsonSerializer.readValue(
                """{"id":"app","groups":[{"name":"g","permissions":[""" +
                    """{"id":"ok","name":"ok","action":"/ok"},""" +
                    """{"id":"admin","name":"admin","action":"*","conditon":{"inRole":{"value":"admin"}}}]}]}""",
                AppPermission::class.java,
            )
        }
        error.path.assert().isEqualTo("$.groups[0].permissions[1]")
        error.message.assert().contains("unknown field [conditon] in a permission")
    }

    @Test
    fun anAppPermissionChecksItsOwnFieldsAndItsGroups() {
        assertThrows<MalformedPolicyException> {
            CoSecJsonSerializer.readValue(
                """{"id":"app","conditon":{"all":{}},"groups":[]}""",
                AppPermission::class.java
            )
        }.path.assert().isEqualTo("$")
        assertThrows<MalformedPolicyException> {
            CoSecJsonSerializer.readValue(
                """{"id":"app","groups":[{"name":"g","permision":[]}]}""",
                AppPermission::class.java
            )
        }.path.assert().isEqualTo("$.groups[0]")
        assertThrows<MalformedPolicyException> {
            CoSecJsonSerializer.readValue("""{"id":"app","groups":{"name":"g"}}""", AppPermission::class.java)
        }.path.assert().isEqualTo("$.groups")
        assertThrows<MalformedPolicyException> {
            CoSecJsonSerializer.readValue(
                """{"id":"app","groups":[{"name":"g","permissions":"p"}]}""",
                AppPermission::class.java
            )
        }.path.assert().isEqualTo("$.groups[0].permissions")
        assertThrows<MalformedPolicyException> {
            CoSecJsonSerializer.readValue(
                """{"id":"app","condition":{"bool":{"and":"x"}},"groups":[]}""",
                AppPermission::class.java,
            )
        }.path.assert().isEqualTo("$.condition.bool.and")
    }

    @Test
    fun anAppPermissionAcceptsSchemaAndSpaced() {
        val appPermission = CoSecJsonSerializer.readValue(
            """{"${'$'}schema":"x","id":"app","groups":[{"name":"g","description":"d","spaced":true,""" +
                """"permissions":[{"id":"p","name":"p","description":"d","effect":"allow","action":"*"}]}]}""",
            AppPermission::class.java,
        )
        appPermission.groups.single().permissions.single().id.assert().isEqualTo("p")
        CoSecJsonSerializer.readValue("""{"id":"app","groups":[{"name":"g"}]}""", AppPermission::class.java)
            .groups.single().permissions.assert().isEmpty()
    }

    @Test
    fun aRateLimitRuleChecksItsFields() {
        assertThrows<MalformedPolicyException> {
            CoSecJsonSerializer.readValue(
                """{"id":"r","action":"*","permitsPerSecond":1,"windowSecond":60}""",
                RateLimitRule::class.java,
            )
        }.message.assert().contains("unknown field [windowSecond] in a rate limit rule")
        assertThrows<MalformedPolicyException> {
            CoSecJsonSerializer.readValue(
                """{"id":"r","action":{"all":{},"path":"/a"},"permitsPerSecond":1}""",
                RateLimitRule::class.java,
            )
        }.path.assert().isEqualTo("$.action")
        assertThrows<MalformedPolicyException> {
            CoSecJsonSerializer.readValue(
                """{"id":"r","action":"*","condition":{"bool":{"or":"x"}},"permitsPerSecond":1}""",
                RateLimitRule::class.java,
            )
        }.path.assert().isEqualTo("$.condition.bool.or")
        CoSecJsonSerializer.readValue(
            """{"${'$'}schema":"x","id":"r","action":"*","key":"request.remoteIp","permitsPerSecond":1,""" +
                """"windowSeconds":2,"strictFailure":true}""",
            RateLimitRule::class.java,
        ).strictFailure.assert().isTrue()
    }

    @Test
    fun aStatementDecodedAloneNamesItsOwnPath() {
        assertThrows<MalformedPolicyException> {
            CoSecJsonSerializer.readValue("""{"action":"*","efect":"deny"}""", Statement::class.java)
        }.path.assert().isEqualTo("$")
        assertThrows<MalformedPolicyException> {
            CoSecJsonSerializer.readValue("""["*"]""", Statement::class.java)
        }.message.assert().contains("a statement must be an object")
    }

    /**
     * A subclass written against the one-argument constructor may read fields of its own, so it keeps decoding them;
     * its action and condition are still checked.
     */
    @Test
    fun aLegacyStatementSubclassKeepsItsOwnFields() {
        val legacy = object : AbstractJsonStatementDeserializer<Statement>(Statement::class.java) {
            override fun createStatement(
                jsonNode: JsonNode,
                name: String,
                effect: Effect,
                action: ActionMatcher,
                condition: ConditionMatcher
            ): Statement = StatementData(jsonNode.get("label").asString(), effect, action, condition)
        }
        val mapper = CoSecJsonSerializer.rebuild()
            .addModule(SimpleModule().addDeserializer(Statement::class.java, legacy))
            .build()
        mapper.readValue("""{"label":"custom","action":"*"}""", Statement::class.java).name.assert()
            .isEqualTo("custom")
        assertThrows<MalformedPolicyException> {
            mapper.readValue("""{"label":"custom","action":{"all":{},"path":"/a"}}""", Statement::class.java)
        }.path.assert().isEqualTo("$.action")
    }
}
