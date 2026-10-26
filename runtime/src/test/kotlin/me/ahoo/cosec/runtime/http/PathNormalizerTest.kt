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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** The cases of `RequestPathsTest` in `cosec-core`: the rules moved unchanged (RFC 0006 §5.3.1). */
class PathNormalizerTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "/public/../admin",
            "/public/%2e%2e/admin",
            "/public/%2E%2E/admin",
            "/public/%2e%2e%2fadmin",
            "/a/./b",
            "/a/%2e/b",
            "/a/%zz/b",
            "/a/%2",
            "/a/%",
            "/public/..;/admin",
            "/public/..;x=1/admin",
            "/public/.;/admin",
            "..",
            "/a/%٣٣",
        ]
    )
    fun ambiguousPathsAreRejected(path: String) {
        PathNormalizer.normalize(path).assert().isNull()
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "/|/",
            "/admin|/admin",
            "/api/users%20123|/api/users%20123",
            "/api/users/123|/api/users/123",
            "/public/%252e%252e/admin|/public/%252e%252e/admin",
            "/admin;jsessionid=1|/admin",
            "/a;x/b;y=1,2|/a/b",
            "/a%3Bb|/a%3Bb",
            "/a/.b/c..|/a/.b/c..",
            "/caf%C3%A9|/caf%C3%A9",
        ]
    )
    fun safePathsLoseOnlyPathParameters(path: String, normalized: String) {
        PathNormalizer.normalize(path).assert().isEqualTo(normalized)
    }

    @Test
    fun emptyPathIsKept() {
        PathNormalizer.normalize("").assert().isEqualTo("")
    }

    @Test
    fun decoderReadsUtf8AndForms() {
        PercentDecoder.decode("caf%C3%A9+x", plusIsSpace = true).assert().isEqualTo("café x")
        PercentDecoder.decode("a+b%2B", plusIsSpace = false).assert().isEqualTo("a+b+")
        PercentDecoder.decode("plain", plusIsSpace = true).assert().isEqualTo("plain")
        PercentDecoder.decode("%G0", plusIsSpace = false).assert().isNull()
        PercentDecoder.decode("%0g", plusIsSpace = false).assert().isNull()
    }
}
