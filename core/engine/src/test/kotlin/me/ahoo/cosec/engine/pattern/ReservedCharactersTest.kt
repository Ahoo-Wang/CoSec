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

package me.ahoo.cosec.engine.pattern

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.condition.Containment
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * DOC-15: the characters a future pattern syntax needs are compile errors unescaped, in a path, a typed resource and an
 * action pattern, and a backslash writes them, and today's syntax, as text; a path pattern that matches no request path
 * (a backslash, a `;`, a control character, a dot segment, a segment longer than any) does not compile either.
 */
class ReservedCharactersTest {
    private fun path(pattern: String) = HttpPathPattern.compile(pattern)

    private fun matches(pattern: String, path: String) = path(pattern).matches(HttpPath.parse(path))

    private fun refused(pattern: String) = assertThrownBy<InvalidPatternException> { path(pattern) }

    @ParameterizedTest
    @ValueSource(chars = ['[', ']', '(', ')', '|', '!', '^', '$'])
    fun `a reserved character unescaped is a compile error where it stands, in every kind of pattern`(char: Char) {
        refused("/a/b${char}c").hasMessage(
            "Invalid pattern [/a/b${char}c] at 4: '$char' is reserved for a future pattern syntax; write \\$char for the character"
        )
        assertThrownBy<InvalidPatternException> { ResourcePattern.parse("order/a${char}b") }
            .hasMessageContaining("at 7: '$char' is reserved")
        assertThrownBy<InvalidPatternException> { ResourcePattern.parse("ord${char}er/a") }
            .hasMessageContaining("at 3: '$char' is reserved")
        assertThrownBy<InvalidPatternException> { ActionPattern.parse("order:re${char}ad") }
            .hasMessageContaining("at 8: '$char' is reserved")
        assertThrownBy<InvalidPatternException> { ActionPattern.parse("ord${char}er:*") }
            .hasMessageContaining("at 3: '$char' is reserved")
    }

    @ParameterizedTest
    @ValueSource(chars = ['[', ']', '(', ')', '|', '!', '^', '$'])
    fun `an escaped reserved character is that character as text`(char: Char) {
        val encoded = "%" + char.code.toString(16).uppercase()
        matches("/files/a\\${char}b", "/files/a${char}b").assert().isTrue()
        matches("/files/a\\${char}b", "/files/a${encoded}b").assert().isTrue()
        matches("/files/a\\${char}b", "/files/ab").assert().isFalse()
        ResourcePattern.parse(
            "file/a\\${char}b"
        ).matches(ResourceTarget(Resource("file", "a${char}b"))).assert().isTrue()
        val action = ActionPattern.parse("svc:M\\$char")
        action.matches(Action("svc", "M$char")).assert().isTrue()
        action.toString().assert().isEqualTo("svc:M\\$char")
        ActionPattern.parse(action.toString()).assert().isEqualTo(action)
    }

    @Test
    fun `the paths that motivated it are written`() {
        matches("/odata/\\\$metadata", "/odata/\$metadata").assert().isTrue()
        matches("/odata/\\\$metadata", "/odata/metadata").assert().isFalse()
        matches("/files/a\\(1\\).txt", "/files/a(1).txt").assert().isTrue()
        matches("/files/a\\(1\\).txt", "/files/a%281%29.txt").assert().isTrue()
        matches("/Products\\({id}\\)", "/Products(42)").assert().isTrue()
        path("/Products\\({id}\\)").match(HttpPath.parse("/Products(42)"))?.value(0).assert().isEqualTo("42")
    }

    @Test
    fun `an escaped wildcard, capture or brace is text, never syntax`() {
        matches("/a/\\*", "/a/*").assert().isTrue()
        matches("/a/\\*", "/a/b").assert().isFalse()
        matches("/a/\\*\\*", "/a/**").assert().isTrue()
        matches("/a/\\*\\*", "/a/b/c").assert().isFalse()
        matches("/a/x\\*", "/a/xyz").assert().isFalse()
        matches("/a/\\{id\\}", "/a/{id}").assert().isTrue()
        matches("/a/\\{id\\}", "/a/7").assert().isFalse()
        path("/a/\\{id\\}").variableNames.assert().isEmpty()
        ResourcePattern.parse("order/\\*").matches(ResourceTarget(Resource("order", "*"))).assert().isTrue()
        ResourcePattern.parse("order/\\*").matches(ResourceTarget(Resource("order", "7"))).assert().isFalse()
    }

    /** `?` among escaped `?`: the wildcard is one character, the escaped one is the character `?`. */
    @Test
    fun `an escaped question mark beside a wildcard one is text`() {
        matches("/a\\?b?", "/a?bX").assert().isTrue()
        matches("/a\\?b?", "/aXbX").assert().isFalse()
        matches("/a\\?b?", "/a?b").assert().isFalse()
        HttpPathPattern.compile("/A\\?b?", caseSensitive = false).matches(HttpPath.parse("/a?BZ")).assert().isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["/a\\d", "/a\\.", "/a\\/b", "/a\\", "/a\\ b"])
    fun `a backslash that escapes no metacharacter is a compile error`(pattern: String) {
        refused(pattern).hasMessageContaining("escapes nothing: a backslash escapes one of")
    }

    @Test
    fun `an action and a typed pattern escape what a path cannot hold`() {
        ResourcePattern.parse("file/a\\\\b").matches(ResourceTarget(Resource("file", "a\\b"))).assert().isTrue()
        ActionPattern.parse("svc:a\\\\b").verb.assert().isEqualTo("a\\b")
        ActionPattern.parse("svc:a\\*").matches(Action("svc", "a*")).assert().isTrue()
        ActionPattern.parse("svc:a\\*").matches(Action("svc", "ab")).assert().isFalse()
        assertThrownBy<InvalidPatternException> {
            ActionPattern.parse(
                "svc:a\\:b"
            )
        }.hasMessageContaining("escapes nothing")
        assertThrownBy<InvalidPatternException> {
            ActionPattern.parse(
                "svc:a{b"
            )
        }.hasMessageContaining("no literal here")
        assertThrownBy<InvalidPatternException> {
            ResourcePattern.parse(
                "order/a?b"
            )
        }.hasMessageContaining("no literal here")
    }

    // --- What matches no request path -------------------------------------------------------------------------------

    @Test
    fun `a backslash is no character of a request path`() {
        refused("/a\\\\b").hasMessageContaining("a request path holds no backslash (PIP-14)")
    }

    @ParameterizedTest
    @ValueSource(strings = ["/a;b", "/a;v=1/b", "/*;x"])
    fun `a semicolon is no character of a request path's segment`(pattern: String) {
        refused(pattern).hasMessageContaining("hold no ';'")
    }

    @ParameterizedTest
    @ValueSource(strings = ["/a\u0000", "/a\u001Fb", "/\u007F", "/a?\u0001"])
    fun `a control character is no character of a request path`(pattern: String) {
        refused(pattern).hasMessageContaining("holds no control character (PIP-30)")
    }

    @ParameterizedTest
    @ValueSource(strings = ["/a/./b", "/a/..", "/../a"])
    fun `a dot segment is no segment of a request path`(pattern: String) {
        refused(pattern).hasMessageContaining("holds no dot segment (PIP-5)")
    }

    @Test
    fun `a dot among other text is a literal`() {
        matches("/a/.well-known", "/a/.well-known").assert().isTrue()
        matches("/a/...", "/a/...").assert().isTrue()
    }

    @Test
    fun `a segment longer than a request path's segment matches nothing`() {
        val max = HttpPath.MAX_SEGMENT_LENGTH
        matches("/" + "a".repeat(max), "/" + "a".repeat(max)).assert().isTrue()
        refused("/" + "a".repeat(max + 1)).hasMessageContaining("more than the $max a request path's segment holds")
        refused("/" + "?".repeat(max + 1)).hasMessageContaining("at least ${max + 1} characters")
        refused("/*" + "a".repeat(max + 1)).hasMessageContaining("at least ${max + 1} characters")
        // Escapes count as the character they write.
        path("/" + "\\(".repeat(max)).matches(HttpPath.parse("/" + "(".repeat(max))).assert().isTrue()
    }

    @Test
    fun `an escape counts as one character of the pattern's length`() {
        val segment = "\\(" + "a".repeat(HttpPath.MAX_SEGMENT_LENGTH - 2)
        val longest = ("/" + segment).repeat(8)
        PatternEscapes.unescapedLength(longest).assert().isEqualTo(HttpPathPattern.MAX_LENGTH)
        path(longest).matches(HttpPath.parse(longest.replace("\\(", "("))).assert().isTrue()
    }

    // --- Where the text goes ------------------------------------------------------------------------------------------

    @Test
    fun `the index files an escaped literal by its text`() {
        path("/odata/\\\$metadata/*").prefix.toList().assert().containsExactly(null, "odata", null, "\$metadata", null)
    }

    /** Escaped patterns stay analyzed: a reader that took `\*` for a wildcard would answer "may overlap". */
    @Test
    fun `containment reads escaped literals`() {
        val escaped = ResourcePattern.parse("http:path:/a/\\*/\\(x\\)")
        PatternContainment.disjoint(escaped, ResourcePattern.parse("http:path:/a/b/\\(x\\)")).assert().isTrue()
        PatternContainment.disjoint(escaped, ResourcePattern.parse("http:path:/a/*/\\(y\\)")).assert().isTrue()
        PatternContainment.disjoint(escaped, ResourcePattern.parse("http:path:/a/*/\\(x\\)")).assert().isFalse()
        PatternContainment.contains(ResourcePattern.parse("http:path:/a/*/\\(x\\)"), escaped)
            .assert().isInstanceOf(ResourceContainment.Contained::class.java)
        PatternContainment.contains(escaped, ResourcePattern.parse("http:path:/a/*/\\(x\\)")).answer
            .assert().isEqualTo(Containment.UNKNOWN)
        PatternContainment.disjoint(
            ResourcePattern.parse("order/\\(a\\)"),
            ResourcePattern.parse("order/\\(b\\)")
        ).assert().isTrue()
    }

    @Test
    fun `escape writes exactly what unescape reads`() {
        val text = "a[b]c(d)e|f!g^h\$i\\j*k?l{m}n"
        PatternEscapes.escape(text).assert().isEqualTo("a\\[b\\]c\\(d\\)e\\|f\\!g\\^h\\\$i\\\\j\\*k\\?l\\{m\\}n")
        PatternEscapes.unescape(PatternEscapes.escape(text)).assert().isEqualTo(text)
        PatternEscapes.escape("plain").assert().isSameAs("plain")
        assertThrownBy<InvalidPatternException> { PatternEscapes.unescape("a b") }.hasMessageContaining("not allowed")
    }
}
