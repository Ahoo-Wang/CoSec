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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.Attribute.Kind
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** What [HttpAttributes] answers for each host attribute (RFC 0005 §3.2). */
class HttpAttributesTest {
    private val attributes = HttpAttributes(
        Headers.of(
            mapOf(
                "X-Debug" to listOf("1"),
                "Origin" to listOf("https://App.Example.com:8443"),
                "Referer" to listOf("https://WWW.example.com/page?q=1"),
                "X-Twice" to listOf("a", "b"),
                "X-Same" to listOf("a", "a"),
            )
        ),
        mapOf("cosec.audit.routeId" to "orders"),
    )

    private fun string(kind: Kind, name: String? = null): String? =
        attributes.string(if (name == null) Attribute.of(kind) else Attribute.of(kind, name))

    @Test
    fun headersOriginAndRefererAndTheirHosts() {
        string(Kind.HTTP_HEADER, "x-debug").assert().isEqualTo("1")
        string(Kind.HTTP_HEADER, "x-absent").assert().isNull()
        string(Kind.HTTP_HEADER, "x-same").assert().isEqualTo("a")
        string(Kind.HTTP_ORIGIN).assert().isEqualTo("https://App.Example.com:8443")
        string(Kind.HTTP_REFERER).assert().isEqualTo("https://WWW.example.com/page?q=1")
        string(Kind.HTTP_ORIGIN_HOST).assert().isEqualTo("app.example.com")
        string(Kind.HTTP_REFERER_HOST).assert().isEqualTo("www.example.com")
        string(Kind.REQUEST_ATTRIBUTE, "cosec.audit.routeId").assert().isEqualTo("orders")
        string(Kind.SUBJECT_ID).assert().isNull()
    }

    @Test
    fun aHeaderWithDifferentValuesHasNone() {
        val error = assertThrows<AmbiguousHeaderException> { string(Kind.HTTP_HEADER, "x-twice") }
        error.header.assert().isEqualTo("x-twice")
    }

    @Test
    fun anOriginThatIsNotAUriHasNoHost() {
        val malformed = HttpAttributes(
            Headers.of(mapOf("Origin" to listOf("not a uri"), "Referer" to listOf("/relative"))),
        )
        malformed.string(Attribute.of(Kind.HTTP_ORIGIN_HOST)).assert().isNull()
        malformed.string(Attribute.of(Kind.HTTP_REFERER_HOST)).assert().isNull()
    }

    @Test
    fun otherTypesAreAbsent() {
        attributes.bool(Attribute.of(Kind.SUBJECT_AUTHENTICATED)).assert().isNull()
        attributes.stringSet(Attribute.of(Kind.SUBJECT_ROLES)).assert().isNull()
        attributes.ip(Attribute.of(Kind.ENVIRONMENT_IP)).assert().isNull()
        attributes.time(Attribute.of(Kind.ENVIRONMENT_TIME)).assert().isEqualTo(ConditionInput.NO_TIME)
    }
}
