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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class AttributeTest {
    @ParameterizedTest
    @EnumSource(Attribute.Kind::class)
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
        attr("resource.var.a").equals("resource.var.a").assert().isFalse()
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
}
