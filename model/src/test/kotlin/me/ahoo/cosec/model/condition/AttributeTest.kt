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

package me.ahoo.cosec.model.condition

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class AttributeTest {
    @ParameterizedTest
    @EnumSource(Attribute.Kind::class, names = ["EXTERNAL"], mode = EnumSource.Mode.EXCLUDE)
    fun `every path of the catalog parses to its kind`(kind: Attribute.Kind) {
        val path = if (kind.parameterized) kind.path + "name" else kind.path
        val attribute = attr(path)
        attribute.kind.assert().isEqualTo(kind)
        attribute.type.assert().isEqualTo(kind.type)
        attribute.path.assert().isEqualTo(path)
        attribute.toString().assert().isEqualTo(path)
        attribute.name.assert().isEqualTo(if (kind.parameterized) "name" else "")
    }

    @Test
    fun `paths outside the catalog do not parse`() {
        listOf(
            "subject",
            "subject.name",
            "subject.attributes.",
            "http.header.",
            "resource.var",
            "SUBJECT.ID",
            "",
            "scope.roles.x"
        )
            .forEach { Attribute.parse(it).assert().describedAs(it).isNull() }
    }

    @Test
    fun `names keep their dots and case, header names fold to lower case`() {
        attr("subject.attributes.a.B").name.assert().isEqualTo("a.B")
        attr("http.header.X-Debug").name.assert().isEqualTo("x-debug")
        attr("http.header.X-Debug").assert().isEqualTo(attr("http.header.x-debug"))
        attr("http.header.X-Debug").hashCode().assert().isEqualTo(attr("http.header.x-debug").hashCode())
        attr("http.origin.host").assert().isSameAs(Attribute.of(Attribute.Kind.HTTP_ORIGIN_HOST))
        attr("resource.var.a").assert().isNotEqualTo(attr("resource.attributes.a"))
        attr("resource.var.a").assert().isNotEqualTo(Any())
    }

    @Test
    fun `labels derive from their host`() {
        attr("http.origin.host.labels").host.assert().isEqualTo(attr("http.origin.host"))
        attr("http.referer.host.labels").host.assert().isEqualTo(attr("http.referer.host"))
        attr("http.origin.host").host.assert().isNull()
    }

    @Test
    fun `kinds and names must agree`() {
        runCatching { Attribute.of(Attribute.Kind.HTTP_HEADER) }.isFailure.assert().isTrue()
        runCatching { Attribute.of(Attribute.Kind.SUBJECT_ID, "x") }.isFailure.assert().isTrue()
        runCatching { Attribute.of(Attribute.Kind.HTTP_HEADER, "") }.isFailure.assert().isTrue()
    }

    /** RFC 0006 §5.4.1: external attributes exist only in a catalog of registered providers, with declared types. */
    @Test
    fun `external attributes come from a catalog`() {
        Attribute.parse("ext.geo.inFence").assert().isNull()
        val inFence = Attribute.external("ext.geo.inFence", AttributeType.BOOL)
        inFence.external.assert().isTrue()
        inFence.kind.assert().isEqualTo(Attribute.Kind.EXTERNAL)
        inFence.type.assert().isEqualTo(AttributeType.BOOL)
        inFence.path.assert().isEqualTo("ext.geo.inFence")
        inFence.assert().isEqualTo(Attribute.external("ext.geo.inFence", AttributeType.BOOL))
        attr("subject.id").external.assert().isFalse()

        val catalog = AttributeCatalog.of(listOf(inFence))
        catalog.external("ext.geo.inFence").assert().isSameAs(inFence)
        catalog.external("ext.geo.other").assert().isNull()
        AttributeCatalog.NONE.external("ext.geo.inFence").assert().isNull()
    }

    @Test
    fun `external attributes are well formed`() {
        listOf("ext.", "ext.geo", "ext.geo.", "ext..x", "geo.inFence").forEach {
            Attribute.isExternalPath(it).assert().describedAs(it).isFalse()
            assertThrows<IllegalArgumentException> { Attribute.external(it, AttributeType.STRING) }
        }
        Attribute.isExternalPath("ext.a.b.c").assert().isTrue()
        assertThrows<IllegalArgumentException> { Attribute.external("ext.geo.at", AttributeType.INSTANT) }
        assertThrows<IllegalArgumentException> { Attribute.external("ext.geo.at", AttributeType.LABELS) }
        assertThrows<IllegalArgumentException> { Attribute.of(Attribute.Kind.EXTERNAL, "geo.inFence") }
        val twice =
            listOf(
                Attribute.external("ext.a.b", AttributeType.STRING),
                Attribute.external("ext.a.b", AttributeType.BOOL)
            )
        assertThrows<IllegalArgumentException> { AttributeCatalog.of(twice) }
        assertThrows<IllegalArgumentException> { AttributeCatalog.of(listOf(attr("subject.id"))) }
    }

    /** A condition may read an external attribute only when a provider declares it, and with its declared type. */
    @Test
    fun `the checker resolves external attributes through the catalog`() {
        val catalog = AttributeCatalog.of(listOf(Attribute.external("ext.geo.inFence", AttributeType.BOOL)))
        val condition = Condition.Eq("ext.geo.inFence", Literal.Bool(true))
        (ConditionChecker(catalog = catalog).check(condition) is CheckResult.Valid).assert().isTrue()
        val unknown = ConditionChecker().check(condition) as CheckResult.Invalid
        unknown.errors.single().message.assert().contains("not declared by any registered AttributeProvider")
        val mistyped = ConditionChecker(catalog = catalog).check(Condition.Eq("ext.geo.inFence", Literal.Str("yes")))
        (mistyped is CheckResult.Invalid).assert().isTrue()
    }
}
