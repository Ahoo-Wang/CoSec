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

import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** `SCP-4` (RFC 0008 §3.2): one `CoSec-*` parameter, one value, wherever it is sent. */
class CoSecParametersTest {
    private fun read(query: String? = null, vararg headers: Pair<String, List<String>>): CoSecParameters? =
        CoSecParameters.read(
            HttpFacts("GET", "/", query, Headers.of(headers.toMap()), Cookies.EMPTY, "10.0.0.1", HostScope(null))
        )

    @Test
    fun absentParametersAreEmpty() {
        val parameters = read()!!
        parameters.appId.assert().isEmpty()
        parameters.spaceId.assert().isEmpty()
        parameters.deviceId.assert().isEmpty()
    }

    @Test
    fun headerOrQueryAlone() {
        read(null, "cosec-app-id" to listOf("shop"))!!.appId.assert().isEqualTo("shop")
        read("CoSec-Space-Id=team+a&x=1")!!.spaceId.assert().isEqualTo("team a")
        read("%43oSec-Device-Id=phone")!!.deviceId.assert().isEqualTo("phone")
    }

    /**
     * RC1-52: no `CoSec-*` parameter can carry an unpaired surrogate. A query decodes as UTF-8, which has no encoding for
     * one: a surrogate half written in UTF-8 form (`%ED%A0%80`) decodes to U+FFFD, never to the surrogate, so it is not
     * the id with `?` in its place either. Hosts give header values as ISO-8859-1 characters, which holds no surrogate.
     */
    @Test
    fun aQueryCannotCarryAnUnpairedSurrogate() {
        for (half in listOf("%ED%A0%80", "%ED%B0%80", "%ED%A0%80%ED%B0%80")) {
            val parameters = read("CoSec-App-Id=shop$half&CoSec-Space-Id=team$half&CoSec-Device-Id=d$half")!!
            for (value in listOf(parameters.appId, parameters.spaceId, parameters.deviceId)) {
                Identifiers.isWellFormed(value).assert().describedAs(Identifiers.printable(value)).isTrue()
                value.assert().contains("\uFFFD").doesNotContain("?")
            }
        }
    }

    @Test
    fun sameValueInBothPlacesIsOneValue() {
        val parameters = read("CoSec-App-Id=shop&CoSec-App-Id=shop", "CoSec-App-Id" to listOf("shop", "shop"))!!
        parameters.appId.assert().isEqualTo("shop")
    }

    @Test
    fun onlyBlankValuesAreNotValues() {
        read("CoSec-App-Id=&CoSec-App-Id=%20%09", "CoSec-App-Id" to listOf(" ", ""))!!.appId.assert().isEmpty()
    }

    /** RC1-16: a backend that reads the blank one would be in another space than CoSec checked. */
    @ParameterizedTest
    @ValueSource(strings = ["CoSec-App-Id", "CoSec-Space-Id", "CoSec-Device-Id"])
    fun aBlankValueBesideAValueIsAmbiguous(name: String) {
        read("$name=", name to listOf("s1")).assert().isNull()
        read("$name=s1&$name=%20").assert().isNull()
        read(null, name to listOf(" ", "s1")).assert().isNull()
        read("$name=s1", name to listOf("")).assert().isNull()
    }

    /** RC1-16: blank is ASCII whitespace only; Unicode spaces survive a backend's `trim()`, so they are a value. */
    @Test
    fun unicodeSpacesAreAValue() {
        read("CoSec-Space-Id=%E3%80%80")!!.spaceId.assert().isEqualTo("\u3000")
        read("CoSec-Space-Id=%C2%A0", "CoSec-Space-Id" to listOf("s1")).assert().isNull()
        read("CoSec-Space-Id=%E3%80%80", "CoSec-Space-Id" to listOf("s1")).assert().isNull()
        CoSecParameters.isBlank(" \t\n\u000C\r").assert().isTrue()
        CoSecParameters.isBlank("\u000B").assert().isFalse()
        CoSecParameters.isBlank("\u00A0").assert().isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["CoSec-App-Id", "CoSec-Space-Id", "CoSec-Device-Id"])
    fun differentHeaderAndQueryValuesAreAmbiguous(name: String) {
        read("$name=b", name to listOf("a")).assert().isNull()
        read("$name=a&$name=b").assert().isNull()
        read(null, name to listOf("a", "b")).assert().isNull()
        read("%43oSec-${name.removePrefix("CoSec-")}=b", name to listOf("a")).assert().isNull()
    }

    @Test
    fun malformedValueOfACoSecParameterIsAmbiguous() {
        read("CoSec-App-Id=%zz").assert().isNull()
    }

    @Test
    fun otherQueryParametersAreNotRead() {
        read("other=%zz&%zz=1&CoSec-Request-Id=x")!!.appId.assert().isEmpty()
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
