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

import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** `SCP-4` (RFC 0008 §3.2): one `CoSec-*` header, one value; the query is not read. */
class CoSecParametersTest {
    private fun read(query: String? = null, vararg headers: Pair<String, List<String>>): CoSecParameters? =
        CoSecParameters.read(
            HttpFacts("GET", "/", query, Headers.of(headers.toMap()), Cookies.EMPTY, "10.0.0.1", HostScope(null))
        )

    private fun CoSecParameters.all() = listOf(appId, spaceId, deviceId)

    @Test
    fun absentParametersAreEmpty() {
        val parameters = read()!!
        parameters.appId.assert().isEmpty()
        parameters.spaceId.assert().isEmpty()
        parameters.deviceId.assert().isEmpty()
    }

    @Test
    fun headersAreReadIgnoringCase() {
        read(null, "cosec-app-id" to listOf("shop"))!!.appId.assert().isEqualTo("shop")
        read(null, "CoSec-Space-Id" to listOf("team a"))!!.spaceId.assert().isEqualTo("team a")
        read(null, "COSEC-DEVICE-ID" to listOf("phone"))!!.deviceId.assert().isEqualTo("phone")
    }

    /** A scope in a URL would reach the access logs, so what the query says is neither a value nor a disagreement. */
    @ParameterizedTest
    @ValueSource(strings = ["CoSec-App-Id", "CoSec-Space-Id", "CoSec-Device-Id"])
    fun theQueryIsNotRead(name: String) {
        read("$name=other")!!.all().assert().containsOnly("")
        read("$name=a&$name=b")!!.all().assert().containsOnly("")
        read("$name=%zz")!!.all().assert().containsOnly("")
        read("$name=other", name to listOf("a"))!!.all().assert().contains("a").doesNotContain("other")
    }

    @Test
    fun aRepeatedValueIsOneValue() {
        read(null, "CoSec-App-Id" to listOf("shop", "shop"))!!.appId.assert().isEqualTo("shop")
    }

    @Test
    fun onlyBlankValuesAreNotValues() {
        read(null, "CoSec-App-Id" to listOf(" ", ""))!!.appId.assert().isEmpty()
        read(null, "CoSec-App-Id" to listOf("\t"))!!.appId.assert().isEmpty()
    }

    /** RC1-16: a backend that reads the blank one would be in another space than CoSec checked. */
    @ParameterizedTest
    @ValueSource(strings = ["CoSec-App-Id", "CoSec-Space-Id", "CoSec-Device-Id"])
    fun aBlankValueBesideAValueIsAmbiguous(name: String) {
        read(null, name to listOf(" ", "s1")).assert().isNull()
        read(null, name to listOf("s1", "")).assert().isNull()
    }

    /** RC1-16: blank is ASCII whitespace only; Unicode spaces survive a backend's `trim()`, so they are a value. */
    @Test
    fun unicodeSpacesAreAValue() {
        read(null, "CoSec-Space-Id" to listOf("\u00A0"))!!.spaceId.assert().isEqualTo("\u00A0")
        read(null, "CoSec-Space-Id" to listOf("\u00A0", "s1")).assert().isNull()
        read(null, "CoSec-Space-Id" to listOf("\u3000", "s1")).assert().isNull()
        CoSecParameters.isBlank(" \t\n\u000C\r").assert().isTrue()
        CoSecParameters.isBlank("\u000B").assert().isFalse()
        CoSecParameters.isBlank("\u00A0").assert().isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["CoSec-App-Id", "CoSec-Space-Id", "CoSec-Device-Id"])
    fun differentHeaderValuesAreAmbiguous(name: String) {
        read(null, name to listOf("a", "b")).assert().isNull()
        read(null, name to listOf("a"), name.lowercase() to listOf("b")).assert().isNull()
    }

    @Test
    fun headersIgnoreCaseAndCookiesDoNot() {
        val headers = Headers.of(mapOf("A" to listOf("1"), "a" to listOf("2")))
        headers.values("a").assert().containsExactly("1", "2")
        headers.values("missing").assert().isEmpty()
        Headers.EMPTY.values("a").assert().isEmpty()
        val cookies = Cookies.of(mapOf("a" to listOf("1")))
        cookies.values("a").assert().containsExactly("1")
        cookies.values("A").assert().isEmpty()
    }
}
