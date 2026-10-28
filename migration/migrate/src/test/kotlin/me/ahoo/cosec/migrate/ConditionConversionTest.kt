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

import me.ahoo.cosec.migrate.Fixtures7x.app
import me.ahoo.cosec.migrate.Fixtures7x.appPermission
import me.ahoo.cosec.migrate.Fixtures7x.permission
import me.ahoo.cosec.migrate.Fixtures7x.policy
import me.ahoo.cosec.migrate.Fixtures7x.source
import me.ahoo.cosec.migrate.Fixtures7x.statement
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.runtime.json.CoSecJsonSerializer
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Conditions in whole policies: where they land in 8.0 statements, and rate limit rules. */
class ConditionConversionTest {
    @TempDir
    lateinit var temp: Path

    private fun platformPolicy(json: String): PolicyDefinition {
        val conversion = Converter().convert(source(listOf("p" to json)))
        conversion.report.rejections.assert().isEmpty()
        return conversion.documents.getValue(SnapshotKey.Platform).policies.single()
    }

    private fun render(condition: Condition?) = StatementTranslator.render(condition)

    @Test
    fun `a path condition on request path becomes the resource of an all action`() {
        val whole =
            platformPolicy(
                policy(
                    "p",
                    statements = listOf(
                        statement(condition = """{"path":{"part":"request.path","pattern":"/a/{id}"}}""")
                    )
                )
            )
        whole.statements.single().resources.single().pattern.assert().isEqualTo("http:path:/a/{id}")
        whole.statements.single().condition.assert().isNull()

        val member = platformPolicy(
            policy(
                "p",
                statements = listOf(
                    statement(
                        """{"all":{"method":"GET"}}""",
                        condition = """{"bool":{"and":[{"authenticated":{}},{"path":{"part":"request.path","pattern":"/a/**","options":{"caseSensitive":false}}}]}}""",
                    ),
                ),
            ),
        ).statements.single()
        member.actions.assert().containsExactly("http:GET")
        member.resources.single().caseSensitive.assert().isFalse()
        render(member.condition).assert().isEqualTo("eq(subject.authenticated,b:true,false)")
    }

    @Test
    fun `a path condition stays a condition, rejected, where the rest reads path variables`() {
        val condition = """{"bool":{"and":[{"path":{"part":"request.path","pattern":"/a/{id}"}},{"eq":{"part":"request.path.var.id","value":""}}]}}"""
        val report = Converter().convert(
            source(listOf("p" to policy("p", statements = listOf(statement(condition = condition)))))
        ).report
        // 7.x refuses it too (its PathVariableScope); the converter would not widen it even if it loaded.
        report.rejections.map { it.kind to it.location }.assert().containsExactlyInAnyOrder(
            RejectionKind.UNDECODABLE to "policy:p",
            RejectionKind.CONDITION_NOT_TRANSLATED to "policy:p#$.statements[0].condition.bool.and[0].path",
        )
    }

    @Test
    fun `a path condition beside a path action is rejected`() {
        val report = Converter().convert(
            source(
                listOf(
                    "p" to policy("p", statements = listOf(statement("\"/x/**\"", condition = """{"path":{"part":"request.path","pattern":"/a/**"}}""")))
                )
            ),
        ).report
        report.rejections.single().location.assert().isEqualTo("policy:p#$.statements[0].condition.path")
    }

    @Test
    fun `a policy condition read the same way in both directions stays on the policy`() {
        val converted =
            platformPolicy(
                policy(
                    "p",
                    condition = """{"authenticated":{}}""",
                    statements = listOf(statement(), statement(effect = "deny", name = "d"))
                )
            )
        render(converted.condition).assert().isEqualTo("eq(subject.authenticated,b:true,false)")
        converted.statements.forEach { it.condition.assert().isNull() }
    }

    @Test
    fun `a policy condition read differently by direction moves into each statement`() {
        val converted = platformPolicy(
            policy(
                "p",
                condition = """{"inRole":{"value":"admin"}}""",
                statements = listOf(
                    statement(),
                    statement(effect = "deny", name = "d", condition = """{"authenticated":{}}""")
                ),
            ),
        )
        converted.condition.assert().isNull()
        render(converted.statements[0].condition).assert().isEqualTo("contains(s:5:admin,scope.roles)")
        render(converted.statements[1].condition).assert()
            .isEqualTo("and(contains(s:5:admin,subject.roles),eq(subject.authenticated,b:true,false))")
    }

    @Test
    fun `the app condition guards every permission policy`() {
        val conversion = Converter().convert(
            source(
                apps = listOf(
                    app(
                        "order",
                        appPermission(
                            "order",
                            permission("read"),
                            permission("purge", effect = "deny"),
                            condition = """{"inTenant":{"value":"user"}}"""
                        ),
                        "clerk" to """["read"]""",
                        "admin" to """["*"]""",
                    ),
                ),
            ),
        )
        conversion.report.rejections.assert().isEmpty()
        val policies = conversion.documents.getValue(SnapshotKey.App("order")).policies
        policies.forEach { render(it.condition).assert().startsWith("not(in(subject.tenant") }
        policies.last().statements.map { it.effect }.assert().containsExactly(Effect.ALLOW, Effect.DENY)
    }

    @Test
    fun `a statement whose condition never holds is rejected`() {
        val report = Converter().convert(
            source(
                listOf("p" to policy("p", statements = listOf(statement(condition = """{"all":{"negate":true}}"""))))
            )
        ).report
        report.rejections.single().message.assert().contains("never hold")
    }

    @Test
    fun `rate limit rules translate with their key, in the DENY direction`() {
        val rules = Files.writeString(
            temp.resolve("rate-limit.json"),
            """
            [{"id":"per-user","action":{"path":{"pattern":"/api/**","method":"POST"}},"condition":{"inRole":{"value":"trial"}},
              "key":"context.principal.id","permitsPerSecond":2.5,"windowSeconds":10,"strictFailure":true},
             {"id":"per-ip","action":"*","key":"request.remoteIp","permitsPerSecond":100}]
            """.trimIndent(),
        )
        val source = source().withRateLimitRules(ExportDirectory.rateLimitRules(rules))
        source.inputSha256.assert().isNotEqualTo(source().inputSha256)
        val conversion = Converter().convert(source)
        conversion.report.rejections.assert().isEmpty()
        conversion.report.counts["rateLimitRules"].assert().isEqualTo(2)
        conversion.report.semanticChanges.filter { it.change == "RATE-LIMIT" }.map { it.location }.assert()
            .containsExactly("rate-limit.json[0]", "rate-limit.json[1]")
        // inRole limits in the DENY direction: any space, never fewer requests than 7.x.
        conversion.report.semanticChanges.single { it.change == "D1" }.shape.assert().contains("DENY direction")
        val out = temp.resolve("out")
        ConvertedDirectory.write(conversion, out)
        // In the platform document, which compiles with them (RFC 0006 §8.1).
        val platform = CoSecJsonSerializer.readTree(Files.readAllBytes(out.resolve(ConvertedDirectory.PLATFORM_FILE)))
        val written = platform.get("rateLimits")
        written.size().assert().isEqualTo(2)
        written.get(0).let { rule ->
            rule.get("id").asString().assert().isEqualTo("per-user")
            rule.get("action").get(0).asString().assert().isEqualTo("http:POST")
            rule.get("resource").get(0).asString().assert().isEqualTo("http:path:/api/**")
            rule.get("condition").toString().assert().isEqualTo("""{"in":["trial","subject.roles"]}""")
            rule.get("key").asString().assert().isEqualTo("subject.id")
            rule.get("permitsPerSecond").asDouble().assert().isEqualTo(2.5)
            rule.get("windowSeconds").asLong().assert().isEqualTo(10L)
            rule.get("strictFailure").asBoolean().assert().isTrue()
        }
        written.get(1).get("key").asString().assert().isEqualTo("environment.ip")
        written.get(1).has("condition").assert().isFalse()
    }

    @Test
    fun `rate limit rules that do not translate are rejected`() {
        val rules = Files.writeString(
            temp.resolve("rate-limit.json"),
            """
            [{"id":"a","action":"*","permitsPerSecond":1},
             {"id":"a","action":"*","permitsPerSecond":1},
             {"id":"b","action":"*","key":"request.nothing","permitsPerSecond":1},
             {"id":"c","action":"*","condition":{"eq":{"part":"request.path","value":"/A","ignoreCase":true}},"permitsPerSecond":1},
             {"id":"d","action":"*"}]
            """.trimIndent(),
        )
        val report = Converter().convert(source().withRateLimitRules(ExportDirectory.rateLimitRules(rules))).report
        report.rejections.map { it.kind to it.location }.assert().containsExactlyInAnyOrder(
            RejectionKind.DUPLICATE_ID to "rate-limit.json[1]",
            // 7.x itself refuses a key that is not a part.
            RejectionKind.UNDECODABLE to "rate-limit.json[2]",
            RejectionKind.CONDITION_NOT_TRANSLATED to "rate-limit.json[2].key",
            RejectionKind.CONDITION_NOT_TRANSLATED to "rate-limit.json[3].condition.eq",
            RejectionKind.UNDECODABLE to "rate-limit.json[4]",
        )
    }

    @Test
    fun `an export reads its rate limit rules, one object or an array`() {
        val directory = Files.createDirectories(temp.resolve("export"))
        Files.writeString(directory.resolve(ExportDirectory.POLICIES_FILE), "[]")
        Files.writeString(directory.resolve(ExportDirectory.GLOBAL_POLICIES_FILE), "[]")
        Files.writeString(directory.resolve(ExportDirectory.APP_ROLE_PERMISSIONS_FILE), "[]")
        Files.writeString(
            directory.resolve(ExportDirectory.RATE_LIMIT_RULES_FILE),
            """{"id":"one","action":"*","permitsPerSecond":1}"""
        )
        ExportDirectory.read(directory).rateLimitRules.single().location.assert().isEqualTo("rate-limit-rules.json[0]")
    }
}
