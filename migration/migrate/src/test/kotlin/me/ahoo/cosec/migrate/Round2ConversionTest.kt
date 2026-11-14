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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.migrate.Fixtures7x.policy
import me.ahoo.cosec.migrate.Fixtures7x.source
import me.ahoo.cosec.migrate.Fixtures7x.statement
import me.ahoo.cosec.migrate.v7.api.policy.ActionMatcher
import me.ahoo.cosec.migrate.v7.serialization.V7JsonSerializer
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.random.Random

/**
 * Round 2 review of host and tools: what 8.0 decides otherwise than 7.x, which a conversion must not carry over in the
 * direction that widens access. Addition A: `PIP-18` decides a HEAD request as GET (and `DOC-8` refuses `http:HEAD`).
 * Addition C: 7.x request attributes and `resource.*` were set by request attributes appenders, which 8.0 does not have.
 */
class Round2ConversionTest {
    private fun convert(vararg statements: String) =
        Converter().convert(
            source(listOf("p" to policy("p", type = "global", statements = statements.toList())), setOf("p"))
        )

    private fun pathAction(vararg methods: String) =
        """{"path":{"pattern":"/r/**","method":[${methods.joinToString(",") { "\"$it\"" }}]}}"""

    private fun rejected(conversion: Conversion, kind: RejectionKind) =
        conversion.report.rejections.filter { it.kind == kind }

    private fun platformStatements(conversion: Conversion) =
        conversion.documents.getValue(SnapshotKey.Platform).policies.single { it.id.value == "p" }.statements

    /** What 7.x's action matcher answers for [method] on /r/1. */
    private fun matches7x(action: String, method: String): Boolean {
        val matcher = V7JsonSerializer.readValue(action, ActionMatcher::class.java)
        val (request, context) = Facts.random(Random(0)).copy(path = "/r/1", method = method).request()
        return matcher.match(request, context)
    }

    /** What the converted actions answer for a request with [method], decided as 8.0 decides it (`PIP-18`). */
    private fun matches8(actions: List<String>, method: String): Boolean {
        val decidedAs = if (method == "HEAD") "GET" else method
        return actions.any { ActionPattern.parse(it).matches(Action("http", decidedAs)) }
    }

    @ParameterizedTest
    @CsvSource("HEAD", "HEAD POST")
    fun `a DENY on HEAD without GET is refused`(methods: String) {
        val conversion = convert(statement(pathAction(*methods.split(' ').toTypedArray()), effect = "deny"))
        conversion.documents.assert().isEmpty()
        rejected(conversion, RejectionKind.ACTION_NOT_TRANSLATED).single().message.assert()
            .contains("cannot deny HEAD apart from GET").contains("PIP-18")
    }

    /** Differential: the 8.0 DENY matches every request the 7.x one matched, HEAD ones included. */
    @Test
    fun `HEAD beside GET is GET, and the DENY still matches what 7x denied`() {
        val action = pathAction("GET", "HEAD")
        val conversion = convert(statement(action, effect = "deny"))
        conversion.report.rejections.assert().isEmpty()
        val actions = platformStatements(conversion).single().actions
        actions.assert().containsExactly("http:GET")
        for (method in listOf("GET", "HEAD", "POST")) {
            if (matches7x(action, method)) {
                matches8(actions, method).assert().describedAs(method).isTrue()
            }
        }
    }

    /** Differential: an ALLOW is never wider than 7.x's, but for HEAD where GET is allowed, which PIP-18 reports. */
    @Test
    fun `an ALLOW on HEAD alone is dropped and recorded, one with others loses HEAD`() {
        val alone = convert(statement(pathAction("HEAD")))
        alone.report.rejections.assert().isEmpty()
        platformStatements(alone).assert().isEmpty()
        alone.report.semanticChanges.filter { it.change == "PIP-18" && it.location != "conversion" }.single().shape
            .assert().contains("an ALLOW on method HEAD alone is dropped")
        val action = pathAction("HEAD", "POST")
        val others = convert(statement(action))
        val actions = platformStatements(others).single().actions
        actions.assert().containsExactly("http:POST")
        for (method in listOf("GET", "HEAD", "POST")) {
            if (matches8(actions, method)) {
                matches7x(action, method).assert().describedAs(method).isTrue()
            }
        }
    }

    @Test
    fun `every conversion reports once what PIP-18 changes`() {
        val changes = convert(statement()).report.semanticChanges.filter { it.location == "conversion" }
        changes.single().change.assert().isEqualTo("PIP-18")
        changes.single().shape.assert().contains("a HEAD request is decided as GET")
    }

    private fun on(part: String, value: String) = """{"eq":{"part":"$part","value":"$value"}}"""

    @Test
    fun `a DENY on method HEAD but not GET is refused, one that holds on GET is kept`() {
        val head = convert(statement(effect = "deny", condition = on("request.method", "HEAD")))
        rejected(head, RejectionKind.CONDITION_NOT_TRANSLATED).single().message.assert()
            .contains("in the DENY direction").contains("method HEAD but not GET")
        val both = convert(
            statement(effect = "deny", condition = """{"in":{"part":"request.method","value":["GET","HEAD"]}}"""),
        )
        both.report.rejections.assert().isEmpty()
        val allow = convert(statement(condition = on("request.method", "HEAD")))
        allow.report.rejections.assert().isEmpty()
        allow.report.semanticChanges.map { it.change }.assert().contains("NOT-IN-8.0")
    }

    /** Addition C: 7.x's ip2region appender set `ipRegion`; 8.0 sets no request attribute but the gateway's route id. */
    @Test
    fun `a DENY on a request attribute only 7x set is refused`() {
        val geo =
            convert(
                statement(
                    effect = "deny",
                    condition = """{"startsWith":{"part":"request.attributes.ipRegion","value":"中国"}}"""
                )
            )
        geo.documents.assert().isEmpty()
        rejected(geo, RejectionKind.CONDITION_NOT_TRANSLATED).single().message.assert()
            .contains("request.attributes.ipRegion").contains("ext.ip2region.country").contains("ext.ip2region.province")
            .contains("ext.ip2region.city").contains("ext.ip2region.isp")
        val resource = convert(statement(effect = "deny", condition = on("resource.owner", "bob")))
        rejected(resource, RejectionKind.CONDITION_NOT_TRANSLATED).single().message.assert().contains("resource.owner")
    }

    @Test
    fun `what 8_0 sets, a DENY that holds on the absent value, and an ALLOW translate`() {
        convert(statement(effect = "deny", condition = on("request.attributes.cosec.audit.routeId", "admin")))
            .report.rejections.assert().isEmpty()
        // Absent in 8.0, "" in 7.x: the DENY holds wherever 7.x's held, and more often; the ALLOW would hold always.
        convert(statement(effect = "deny", condition = on("request.attributes.ipRegion", "")))
            .report.rejections.assert().isEmpty()
        rejected(convert(statement(condition = on("resource.owner", ""))), RejectionKind.CONDITION_NOT_TRANSLATED)
            .single().message.assert().contains("would hold for every request")
        val allow = convert(statement(condition = on("request.attributes.ipRegion", "x")))
        allow.report.rejections.assert().isEmpty()
        allow.report.semanticChanges.filter { it.change == "NOT-IN-8.0" }.single().shape.assert()
            .contains("in the ALLOW direction")
        // Under negate a DENY reads its inner condition in the ALLOW direction, where absence only narrows it.
        convert(
            statement(
                effect = "deny",
                condition = """{"eq":{"part":"request.attributes.ipRegion","value":"x","negate":true}}"""
            )
        )
            .report.rejections.assert().isEmpty()
    }

    private fun region(condition: String, effect: String = "deny") = convert(statement(effect = effect, condition = condition))

    /** RFC 0006 §5.4.5: the one shape of 7.x's region that says one 8.0 attribute, the country prefix. */
    @ParameterizedTest
    @CsvSource(
        delimiterString = "@@",
        value = [
            """{"startsWith":{"part":"request.attributes.ipRegion","value":"中国|"}}""",
            """{"regular":{"part":"request.attributes.ipRegion","pattern":"^中国\\|.*$"}}""",
        ],
    )
    fun `a country prefix reads ext_ip2region_country, declared by the platform document`(condition: String) {
        val conversion = region(condition)
        conversion.report.rejections.assert().isEmpty()
        val platform = conversion.documents.getValue(SnapshotKey.Platform)
        StatementTranslator.render(platform.policies.single { it.id.value == "p" }.statements.single().condition)
            .assert().contains("ext.ip2region.country").contains("s:2:中国")
        platform.attributes!!.map { it.path }.assert().containsExactly("ext.ip2region.country")
        conversion.report.semanticChanges.single { it.change == "IP2REGION" && it.location == "conversion" }.shape
            .assert().contains("cosec-ip2region").contains("ATR-9")
        // By itself, as an instance without the provider compiles it, and beside the provider's own declarations.
        PolicyDocumentCompiler.forPlatform(platform).compile(platform)
        val provider = AttributeCatalog.of(
            listOf("country", "province", "city", "isp").map { Attribute.external("ext.ip2region.$it", AttributeType.STRING) },
        )
        PolicyDocumentCompiler.forPlatform(platform, provider).compile(platform)
    }

    @Test
    fun `a conversion that reads no country declares nothing`() {
        val conversion = convert(statement())
        conversion.documents.getValue(SnapshotKey.Platform).attributes.assert().isNull()
        conversion.report.semanticChanges.none { it.change == "IP2REGION" }.assert().isTrue()
    }

    /** Anything else on the region is by direction: the region field cannot be read, and `0` fields are absent. */
    @ParameterizedTest
    @CsvSource(
        delimiterString = "@@",
        value = [
            """{"eq":{"part":"request.attributes.ipRegion","value":"中国|0|上海|上海市|电信"}}""",
            """{"in":{"part":"request.attributes.ipRegion","value":["中国|0|上海|上海市|电信"]}}""",
            """{"startsWith":{"part":"request.attributes.ipRegion","value":"中国|0|上海|"}}""",
            """{"startsWith":{"part":"request.attributes.ipRegion","value":"0|"}}""",
        ],
    )
    fun `any other region condition is refused as a DENY and narrows an ALLOW`(condition: String) {
        val deny = region(condition)
        deny.documents.assert().isEmpty()
        rejected(deny, RejectionKind.CONDITION_NOT_TRANSLATED).single().message.assert()
            .contains("ext.ip2region.province").contains("by hand")
        val allow = region(condition, effect = "allow")
        allow.report.rejections.assert().isEmpty()
        allow.report.semanticChanges.map { it.change }.assert().contains("NOT-IN-8.0")
    }
}
