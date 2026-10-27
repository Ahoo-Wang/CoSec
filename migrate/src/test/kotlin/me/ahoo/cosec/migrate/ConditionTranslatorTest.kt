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

import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.random.Random

/**
 * Translation correctness (RFC 0005 §7.1, RFC 0008 §4.1): each 7.x condition of [CORPUS] and its translation are
 * evaluated on the same requests, and must agree:
 *
 * - exactly, for the rows §7.1 marks as the same ([Row.exact]), in both directions;
 * - in the safe direction otherwise: in the ALLOW direction the translation matches only requests 7.x matched; in the
 *   DENY direction it matches every request 7.x matched. A translation never lets through what 7.x refused.
 *
 * Requests are drawn from [Facts] (seeded): absent attributes, case variants, non-ASCII letters that fold to ASCII,
 * line terminators, time-zone and daylight-saving boundaries.
 */
class ConditionTranslatorTest {
    data class Row(val json: String, val exact: Boolean)

    private fun translate(json: String, deny: Boolean, rejections: MutableList<Rejection> = ArrayList(), changes: MutableList<SemanticChange> = ArrayList()): Translated? =
        ConditionTranslator(
            { kind, location, message -> rejections += Rejection(kind, location, message) },
            { changes += it }
        )
            .translate(CoSecJsonSerializer.readTree(json), "c", deny)

    @Test
    fun `every row agrees with 7x on adversarial requests, never widening`() {
        val random = Random(SEED)
        val facts = List(REQUESTS) { Facts.random(random) } + FIXED
        Matchers7x.use {
            for (row in CORPUS) {
                val matcher = Matchers7x.matcher(row.json)
                for (deny in listOf(false, true)) {
                    val rejections = ArrayList<Rejection>()
                    val translated = translate(row.json, deny, rejections)
                    if (translated == null) {
                        if (row.exact) fail("${row.json} must translate exactly (deny=$deny): $rejections")
                        continue
                    }
                    val ast = Matchers7x.evaluator(translated)
                    for (request in facts) {
                        val old = Matchers7x.matches(matcher, request)
                        val new = ast(request)
                        val agrees = when {
                            row.exact -> old == new
                            deny -> !old || new
                            else -> !new || old
                        }
                        if (!agrees) {
                            fail(
                                "${row.json} (deny=$deny): 7.x=$old 8.0=$new on $request; translated as ${render(
                                    translated
                                )}"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun render(translated: Translated) = when (translated) {
        is Translated.Ast -> StatementTranslator.render(translated.condition)
        else -> translated.toString()
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"eq":{"part":"request.remoteIp","value":"10.0.0.1"}}|false|CONDITION_NOT_TRANSLATED|c.eq""",
            """{"contains":{"part":"request.path","value":"admin"}}|false|CONDITION_NOT_TRANSLATED|c.contains""",
            """{"eq":{"part":"request.header.x-tenant","value":"admin","ignoreCase":true}}|true|CONDITION_NOT_TRANSLATED|c.eq""",
            """{"startsWith":{"part":"request.path","value":"/admin","ignoreCase":true}}|true|CONDITION_NOT_TRANSLATED|c.startsWith""",
            """{"regular":{"part":"request.path","pattern":"^/admin.*$"}}|false|CONDITION_NOT_TRANSLATED|c.regular""",
            // Review C1: not letters, but with case mappings 7.x folds; a DENY would stop matching their other case.
            """{"eq":{"part":"request.header.x-debug","value":"\u24B6","ignoreCase":true}}|true|CONDITION_NOT_TRANSLATED|c.eq""",
            """{"startsWith":{"part":"request.header.x-debug","value":"\u2160","ignoreCase":true}}|true|CONDITION_NOT_TRANSLATED|c.startsWith""",
            """{"regular":{"part":"request.header.x-debug","pattern":"\u24B6"}}|true|CONDITION_NOT_TRANSLATED|c.regular""",
            """{"regular":{"part":"request.header.x-debug","pattern":"\u2160.*"}}|true|CONDITION_NOT_TRANSLATED|c.regular""",
            """{"regular":{"part":"request.path","pattern":"^/a[0-9]+$"}}|true|CONDITION_NOT_TRANSLATED|c.regular""",
            """{"regular":{"part":"request.path","pattern":"(unclosed"}}|true|UNDECODABLE|c.regular""",
            """{"endsWith":{"part":"request.origin.host","value":".example.com"}}|true|CONDITION_NOT_TRANSLATED|c.endsWith""",
            """{"endsWith":{"part":"request.path","value":"/x","ignoreCase":true}}|true|CONDITION_NOT_TRANSLATED|c.endsWith""",
            """{"eq":{"part":"request.path","value":"#{principal.name}"}}|false|CONDITION_NOT_TRANSLATED|c.eq""",
            """{"eq":{"part":"request.path","value":"/u/#{principal.id}"}}|false|CONDITION_NOT_TRANSLATED|c.eq""",
            """{"eq":{"part":"context.principal.id","value":"#{tenant.tenantId}"}}|false|CONDITION_NOT_TRANSLATED|c.eq""",
            """{"inRole":{"value":"@team"}}|false|CONDITION_NOT_TRANSLATED|c.inRole""",
            """{"inRole":{"value":""}}|true|CONDITION_NOT_TRANSLATED|c.inRole""",
            """{"inTenant":{"value":"nobody"}}|false|UNDECODABLE|c.inTenant""",
            """{"path":{"part":"request.path","pattern":"/a/**"}}|false|CONDITION_NOT_TRANSLATED|c.path""",
            """{"spel":{"value":"true"}}|false|EXPRESSION_CONDITION|c.spel""",
            """{"ognl":{"value":"true"}}|true|EXPRESSION_CONDITION|c.ognl""",
            """{"mine":{}}|false|CUSTOM_CONDITION|c.mine""",
            """{"eq":{"part":"request.nothing","value":"a"}}|false|CONDITION_NOT_TRANSLATED|c.eq""",
            """{"eq":{"value":"a"}}|false|UNDECODABLE|c.eq""",
            """{"eq":{"part":"request.path","value":1}}|false|UNDECODABLE|c.eq""",
            """{"in":{"part":"request.path","value":"a"}}|false|UNDECODABLE|c.in""",
            """{"time":{"notBefore":"2026-01-01T00:00:00Z","when":"now"}}|false|UNDECODABLE|c.time""",
            """{"time":{"daysOfWeek":["FUNDAY"]}}|false|UNDECODABLE|c.time""",
            """{"bool":[]}|false|UNDECODABLE|c.bool""",
            """{"a":{},"b":{}}|false|UNDECODABLE|c""",
        ],
    )
    fun `what has no exact or safe translation is rejected at its location`(
        json: String,
        deny: Boolean,
        kind: RejectionKind,
        location: String
    ) {
        val rejections = ArrayList<Rejection>()
        translate(json, deny, rejections).assert().isNull()
        rejections.first().kind.assert().isEqualTo(kind)
        rejections.first().location.assert().isEqualTo(location)
    }

    @Test
    fun `intended differences and narrowings are recorded with their location`() {
        fun changes(json: String, deny: Boolean) = ArrayList<SemanticChange>().also {
            translate(
                json,
                deny,
                changes = it
            )
        }
        changes("""{"inRole":{"value":"admin"}}""", false).single().let {
            it.change.assert().isEqualTo("D1")
            it.location.assert().isEqualTo("c.inRole")
            it.shape.assert().contains("ALLOW direction")
        }
        changes("""{"inRole":{"value":"admin"}}""", true).single().shape.assert().contains("DENY direction")
        // Under negate, a DENY reads the role in the ALLOW direction.
        changes(
            """{"inRole":{"value":"admin","negate":true}}""",
            true
        ).single().shape.assert().contains("ALLOW direction")
        changes("""{"inRole":{"value":"admin@team"}}""", true).assert().isEmpty()
        changes(
            """{"eq":{"part":"request.attributes.owner","value":"#{principal.id}"}}""",
            false
        ).single().change.assert().isEqualTo("D2")
        changes(
            """{"eq":{"part":"request.attributes.owner","value":"#{principal.id}"}}""",
            true
        ).single().shape.assert().contains("kept")
        changes("""{"regular":{"part":"request.path","pattern":"^/a$"}}""", false).map { it.change }.assert()
            .containsExactlyInAnyOrder("D3", "ASCII-CASE")
        changes(
            """{"regular":{"part":"request.path","pattern":"^/1$"}}""",
            true
        ).map { it.change }.assert().containsExactly("D3")
        changes(
            """{"eq":{"part":"request.path","value":"/A","ignoreCase":true}}""",
            false
        ).single().change.assert().isEqualTo("ASCII-CASE")
        changes("""{"eq":{"part":"request.path","value":"","ignoreCase":false}}""", false).assert().isEmpty()
    }

    @Test
    fun `a template becomes an attribute reference that matches only the principal's own id`() {
        Matchers7x.use {
            for (deny in listOf(false, true)) {
                val translated = translate(
                    """{"eq":{"part":"request.attributes.owner","value":"#{principal.id}"}}""",
                    deny
                ) as Translated.Ast
                // No expression language reaches 8.0: SpEL only reads the 7.x value when converting.
                val text = render(translated)
                text.assert().contains("attr:subject.id").doesNotContain("#{")
                val ast = Matchers7x.evaluator(translated)
                val base = FIXED.first()
                for (id in Facts.PRINCIPAL_IDS) {
                    for (owner in Facts.PRINCIPAL_IDS) {
                        val facts = base.copy(principalId = id, requestAttributes = mapOf("owner" to owner))
                        ast(facts).assert().describedAs("deny=$deny id=$id owner=$owner").isEqualTo(id == owner)
                    }
                }
            }
        }
    }

    @Test
    fun `constant conditions fold away`() {
        translate("""{"all":{}}""", false).assert().isEqualTo(Translated.Always)
        translate("""{"all":{"negate":true}}""", false).assert().isEqualTo(Translated.Never)
        translate("""{"bool":{}}""", false).assert().isEqualTo(Translated.Always)
        translate("""{"bool":{"or":[{"all":{"negate":true}}]}}""", false).assert().isEqualTo(Translated.Never)
        translate("""{"startsWith":{"part":"request.path","value":""}}""", false).assert().isEqualTo(Translated.Always)
        translate("""{"in":{"part":"request.path","value":[]}}""", false).assert().isEqualTo(Translated.Never)
        Translated.not(
            Translated.Ast(
                me.ahoo.cosec.model.condition.Condition.Not(
                    me.ahoo.cosec.model.condition.Condition.Exists("subject.id")
                )
            )
        )
            .let { (it as Translated.Ast).condition }.assert().isInstanceOf(
                me.ahoo.cosec.model.condition.Condition.Exists::class.java
            )
    }

    @Test
    fun `literal regular expressions are recognized, anything else is not`() {
        (LiteralRegex.parse("^/a\\.b$") as LiteralRegex.Exact).choices.assert().containsExactly("/a.b")
        (LiteralRegex.parse("(?:a|b|a)") as LiteralRegex.Exact).choices.assert().containsExactly("a", "b")
        (LiteralRegex.parse("^/api/.*$") as LiteralRegex.Prefix).prefix.assert().isEqualTo("/api/")
        (LiteralRegex.parse("") as LiteralRegex.Exact).choices.assert().containsExactly("")
        listOf(
            "a+",
            "[ab]",
            "\\d",
            "a.*?",
            "(a)|(b)",
            "a\\",
            "x{2}",
            "^(a|b).*"
        ).forEach { LiteralRegex.parse(it).assert().describedAs(it).isNull() }
    }

    companion object {
        const val SEED = 20261026L
        const val REQUESTS = 2_000

        /** Requests that hit the edges on purpose. */
        val FIXED: List<Facts> = run {
            val base = Facts.random(
                Random(1)
            ).copy(authenticated = true, principalId = "alice", tenantId = "acme", roles = setOf("admin"))
            listOf(
                base,
                base.copy(authenticated = false, principalId = "(0)", tenantId = "(0)", roles = emptySet(), principalAttributes = emptyMap()),
                base.copy(headers = emptyMap(), requestAttributes = emptyMap(), resourceAttributes = emptyMap(), pathVariables = emptyMap()),
                base.copy(headers = mapOf("x-tenant" to "Key", "x-debug" to "")),
                base.copy(path = "/ADMIN/x", method = "get"),
                base.copy(requestAttributes = mapOf("owner" to "alice"), principalAttributes = mapOf("owner" to "")),
                base.copy(requestAttributes = emptyMap(), principalAttributes = mapOf("owner" to "")),
                base.copy(authenticated = false, principalId = "(0)", requestAttributes = mapOf("owner" to "(0)")),
            ) + Facts.TIMES.map { base.copy(time = it) }
        }

        private fun exact(json: String) = Row(json, exact = true)

        private fun directional(json: String) = Row(json, exact = false)

        private fun negations(rows: List<Row>): List<Row> = rows + rows.map { row ->
            val node = CoSecJsonSerializer.readTree(row.json) as tools.jackson.databind.node.ObjectNode
            val config = node.properties().first().value
            if (config is tools.jackson.databind.node.ObjectNode) config.put("negate", true)
            Row(node.toString(), row.exact)
        }

        val CORPUS: List<Row> = negations(
            listOf(
                exact("""{"all":{}}"""),
                exact("""{"authenticated":{}}"""),
                exact("""{"inTenant":{"value":"default"}}"""),
                exact("""{"inTenant":{"value":"USER"}}"""),
                exact("""{"inTenant":{"value":"platform"}}"""),
                directional("""{"inRole":{"value":"admin"}}"""),
                directional("""{"inRole":{"value":"admin@team"}}"""),
                exact("""{"eq":{"part":"request.path","value":"/orders/1"}}"""),
                exact("""{"eq":{"part":"request.method","value":"GET"}}"""),
                exact("""{"eq":{"part":"request.appId","value":""}}"""),
                exact("""{"eq":{"part":"request.appId","value":"order"}}"""),
                exact("""{"eq":{"part":"request.spaceId","value":""}}"""),
                exact("""{"eq":{"part":"request.deviceId","value":"d1"}}"""),
                exact("""{"eq":{"part":"request.header.x-tenant","value":""}}"""),
                exact("""{"eq":{"part":"request.header.x-tenant","value":"acme"}}"""),
                exact("""{"eq":{"part":"request.header.x-tenant","value":"1-2","ignoreCase":true}}"""),
                directional("""{"eq":{"part":"request.header.x-tenant","value":"key","ignoreCase":true}}"""),
                exact("""{"eq":{"part":"request.attributes.owner","value":"alice"}}"""),
                exact("""{"eq":{"part":"resource.owner","value":""}}"""),
                exact("""{"eq":{"part":"request.path.var.id","value":"owner-1"}}"""),
                exact("""{"eq":{"part":"context.principal.id","value":"alice"}}"""),
                exact("""{"eq":{"part":"context.principal.id","value":"(0)"}}"""),
                exact("""{"eq":{"part":"context.tenantId","value":"acme"}}"""),
                exact("""{"eq":{"part":"context.principal.attributes.owner","value":""}}"""),
                exact("""{"eq":{"part":"request.origin","value":"https://example.com"}}"""),
                exact("""{"eq":{"part":"request.origin.host","value":""}}"""),
                exact("""{"eq":{"part":"request.referer.host","value":"example.com"}}"""),
                directional("""{"eq":{"part":"request.attributes.owner","value":"#{principal.id}"}}"""),
                directional(
                    """{"eq":{"part":"request.attributes.owner","value":"#{principal.attributes['owner']}"}}"""
                ),
                directional("""{"eq":{"part":"resource.owner","value":"#{tenant.tenantId}"}}"""),
                exact("""{"in":{"part":"request.path","value":["/orders","/orders/1"]}}"""),
                exact("""{"in":{"part":"request.header.x-debug","value":["","1"]}}"""),
                exact("""{"in":{"part":"context.principal.id","value":["(0)","alice"]}}"""),
                exact("""{"in":{"part":"request.path","value":[]}}"""),
                exact("""{"startsWith":{"part":"request.path","value":"/orders"}}"""),
                exact("""{"startsWith":{"part":"request.path","value":""}}"""),
                exact("""{"startsWith":{"part":"context.principal.id","value":"(0"}}"""),
                directional("""{"startsWith":{"part":"request.path","value":"/Orders","ignoreCase":true}}"""),
                exact("""{"startsWith":{"part":"request.header.x-tenant","value":"1","ignoreCase":true}}"""),
                exact("""{"endsWith":{"part":"request.origin.host","value":".example.com","ignoreCase":true}}"""),
                exact("""{"endsWith":{"part":"request.referer.host","value":".EXAMPLE.com","ignoreCase":true}}"""),
                directional("""{"regular":{"part":"request.path","pattern":"^/orders/1$"}}"""),
                directional("""{"regular":{"part":"request.path","pattern":"/ORDERS"}}"""),
                directional("""{"regular":{"part":"request.method","pattern":"^(get|post)$"}}"""),
                exact("""{"regular":{"part":"request.header.x-tenant","pattern":"^$"}}"""),
                directional("""{"regular":{"part":"request.header.x-tenant","pattern":"k"}}"""),
                directional("""{"regular":{"part":"request.header.x-tenant","pattern":"\\u00e9"}}"""),
                directional("""{"regular":{"part":"request.path","pattern":"^/1.*$"}}"""),
                directional("""{"regular":{"part":"request.header.x-tenant","pattern":".*"}}"""),
                directional("""{"eq":{"part":"request.header.x-tenant","value":"\u24B6","ignoreCase":true}}"""),
                directional("""{"startsWith":{"part":"request.header.x-tenant","value":"\u2160","ignoreCase":true}}"""),
                directional("""{"regular":{"part":"request.header.x-tenant","pattern":"\u24B6"}}"""),
                exact("""{"time":{"notBefore":"2026-10-26T08:00:00Z","notAfter":"2026-10-27T00:00:00Z"}}"""),
                exact("""{"time":{"notBefore":"2026-10-26T09:00","zone":"Europe/Berlin"}}"""),
                exact("""{"time":{"from":"09:00","to":"18:00","zone":"Europe/Berlin"}}"""),
                exact(
                    """{"time":{"from":"22:00","to":"06:00","daysOfWeek":["mon","FRIDAY"," sun "],"zone":"Asia/Shanghai"}}"""
                ),
                exact("""{"time":{"daysOfWeek":["SAT","SUN"],"zone":"America/New_York"}}"""),
                exact("""{"time":{"from":"02:00","to":"03:00","zone":"Europe/Berlin"}}"""),
                exact("""{"bool":{"and":[{"authenticated":{}},{"eq":{"part":"request.method","value":"GET"}}]}}"""),
                exact(
                    """{"bool":{"or":[{"inTenant":{"value":"platform"}},{"eq":{"part":"request.header.x-debug","value":""}}]}}"""
                ),
                exact(
                    """{"bool":{"and":[{"authenticated":{}}],"or":[{"eq":{"part":"request.appId","value":""}},{"startsWith":{"part":"request.path","value":"/orders/"}}]}}"""
                ),
                directional(
                    """{"bool":{"and":[{"inRole":{"value":"admin"}},{"bool":{"or":[{"inRole":{"value":"viewer@team","negate":true}}]}}]}}"""
                ),
                exact("""{"bool":{}}"""),
            ),
        )
    }
}
