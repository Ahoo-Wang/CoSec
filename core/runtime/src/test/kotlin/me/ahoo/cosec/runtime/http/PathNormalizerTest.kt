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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.InternalCoSecApi
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
            // Every encoding of a dot segment: the engine's HttpPath does not resolve them.
            "/public/.%2e/admin",
            "/public/%2e./admin",
            "/public/%2E%2e/admin",
            "/public/%2e/admin",
            "/public/%2E/admin",
            "/public/..",
            "/public/%2e%2e",
            "/public/%2e%2e;x/admin",
            "/public/%2E%2E;/admin",
            "/public/.%2e;jsessionid=1/admin",
            "/public/..%2fadmin",
            "/public/..%2Fadmin",
            "/public/..\\admin",
            "/public/..%5cadmin",
            "/public/%2e%2e%5Cadmin",
            "/public\\..\\admin",
            "/public/%2e%2e%3Bx/admin",
            "/public/..%3b/admin",
        ]
    )
    fun ambiguousPathsAreRejected(path: String) {
        PathNormalizer.normalize(path).assert().isNull()
    }

    /**
     * PIP-14: what a backend may read as another path, which a pattern reads as segments of its own. A DENY on the
     * segments under `/admin` does not match `//admin/x` or `/admin%2Fx`, and a broad ALLOW then lets through what a
     * backend that merges slashes or decodes separators serves as `/admin/x`.
     */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "//",
            "//admin/x",
            "/admin//x",
            "/a///b",
            "/a//",
            "a//b",
            // Removing path parameters leaves an empty segment.
            "/;x/admin/x",
            "/admin/;x/x",
            "/a;x//b",
            // A backslash, raw or encoded in either case: a pattern reads it inside a segment, some containers and every
            // browser as a separator.
            "/admin\\1",
            "/admin%5C1",
            "/admin%5c1",
            "/a\\",
            "/\\admin/x",
            "/a\\\\b",
            "/a\\/b",
            "/a%5C/b",
            "/a%5c%5cb",
            // An encoded separator, in either case, wherever it is.
            "/admin%2Fx",
            "/admin%2fx",
            "/%2Fadmin/x",
            "/a/%2F",
            "/a/b%2F",
        ]
    )
    fun emptySegmentsAndEncodedSeparatorsAreRejected(path: String) {
        PathNormalizer.normalize(path).assert().isNull()
    }

    /** PIP-14: a trailing separator is not an empty segment before the end, and a double-encoded one decodes to text. */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "/admin/|/admin/",
            "/a/b/|/a/b/",
            "/a/;x|/a/",
            "/a;x/|/a/",
            "/a.json/|/a.json/",
            "/a%20b/|/a%20b/",
            "/|/",
            "/a%252Fb|/a%252Fb",
            "/a%255Cb|/a%255Cb",
        ]
    )
    fun aTrailingSeparatorIsKept(path: String, normalized: String) {
        PathNormalizer.normalize(path).assert().isEqualTo(normalized)
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
            "/a/.b/c..|/a/.b/c..",
            "/caf%C3%A9|/caf%C3%A9",
            "/a..b/...|/a..b/...",
        ]
    )
    fun safePathsLoseOnlyPathParameters(path: String, normalized: String) {
        PathNormalizer.normalize(path).assert().isEqualTo(normalized)
    }

    /**
     * PIP-30: every encoded `;` and control character, in either case of the hex digits, wherever it is in the path,
     * path parameters included: a backend that decodes before it strips path parameters, stops at NUL or drops what
     * follows a control character serves another path than the one decided.
     */
    @Test
    fun encodedSemicolonsAndControlCharactersAreRejected() {
        val refused = (0x00..0x1F) + 0x3B + 0x7F
        refused.size.assert().isEqualTo(34)
        for (value in refused) {
            for (escape in setOf("%%%02X".format(value), "%%%02x".format(value))) {
                for (path in listOf("/admin${escape}x/users", "/$escape", "/a/b$escape", "$escape/a", "/a;x=$escape/b")) {
                    PathNormalizer.normalize(path).assert().describedAs(path).isNull()
                }
            }
        }
    }

    /**
     * PIP-30: what stays as before. A double-encoded `;` or control character decodes to text, a printable character
     * encoded is a character of its segment, and a trailing dot or space is read as written; a raw `;` is a path
     * parameter.
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "/a%253Bb|/a%253Bb",
            "/a%2500b|/a%2500b",
            "/a%251Fb|/a%251Fb",
            "/a%257Fb|/a%257Fb",
            "/a%25b|/a%25b",
            "/a%20b|/a%20b",
            "/a%3Ab%3cc%3D|/a%3Ab%3cc%3D",
            "/a%7Eb|/a%7Eb",
            "/page.|/page.",
            "/page%2e|/page%2e",
            "/page%20|/page%20",
            "/admin;x/users|/admin/users",
        ]
    )
    fun doubleEncodedAndPrintableEscapesAreKept(path: String, normalized: String) {
        PathNormalizer.normalize(path).assert().isEqualTo(normalized)
    }

    /** PIP-30: the reason names the kind of sequence and its segment, never the path. */
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "/admin%3Bx/users|path: an encoded ';' in segment 1",
            "/a/b%3b|path: an encoded ';' in segment 2",
            "/a/b/c%00|path: an encoded control character in segment 3",
            "a%7f/b|path: an encoded control character in segment 1",
            "/a;x=%0A/b|path: an encoded control character in segment 1",
            "/public/%2e%2e/admin|path",
            "/admin%2Fx|path",
        ]
    )
    fun theRejectionNamesTheKindAndTheSegment(path: String, reason: String) {
        PathNormalizer.normalize(path).assert().isNull()
        PathNormalizer.rejection(path).assert().isEqualTo(reason)
    }

    @Test
    fun longPathsAndSegmentsAreRejected() {
        val segment = "a".repeat(PathNormalizer.MAX_SEGMENT_LENGTH)
        PathNormalizer.normalize("/$segment/b").assert().isEqualTo("/$segment/b")
        PathNormalizer.normalize("/${segment}a/b").assert().isNull()
        PathNormalizer.normalize("/b/${segment}a").assert().isNull()
        PathNormalizer.normalize("${segment}a").assert().isNull()
        // Path parameters are removed before segments are measured; the whole path is measured as received.
        PathNormalizer.normalize("/$segment;${"p".repeat(100)}").assert().isEqualTo("/$segment")

        val path = "/" + "a/".repeat((PathNormalizer.MAX_PATH_LENGTH - 1) / 2)
        path.length.assert().isLessThanOrEqualTo(PathNormalizer.MAX_PATH_LENGTH)
        PathNormalizer.normalize(path).assert().isEqualTo(path)
        PathNormalizer.normalize(path + "aa").assert().isNull()
    }

    /** Review L5 of #1200: what the control plane decides without a request is held to the same bounds, as written. */
    @OptIn(InternalCoSecApi::class)
    @Test
    fun boundsApplyAsWritten() {
        val segment = "a".repeat(PathNormalizer.MAX_SEGMENT_LENGTH)
        PathNormalizer.withinBounds("/$segment").assert().isTrue()
        PathNormalizer.withinBounds("/${segment}a").assert().isFalse()
        // Path parameters count: a simulation matches the text it is given.
        PathNormalizer.withinBounds("/a;$segment").assert().isFalse()
        PathNormalizer.withinBounds("order/$segment").assert().isTrue()
        PathNormalizer.withinBounds("/a".repeat(PathNormalizer.MAX_PATH_LENGTH / 2)).assert().isTrue()
        PathNormalizer.withinBounds("/a".repeat(PathNormalizer.MAX_PATH_LENGTH / 2) + "/").assert().isFalse()
        PathNormalizer.withinBounds("").assert().isTrue()
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
