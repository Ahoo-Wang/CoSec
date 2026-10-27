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

package me.ahoo.cosec.snapshot.document

import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotFormatException
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/** RFC 0006 §8.1: rate limit rules are a section of every document, read, written and compiled with it. */
class RateLimitDocumentTest {
    private val compiler = PolicyDocumentCompiler()

    private fun body(rules: String) =
        """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z","rateLimits":[$rules]}""".encodeToByteArray()

    private fun stored(rules: String) = SnapshotDocument(2, 1, Instant.parse("2026-10-26T00:00:00Z"), body(rules))

    private val rule = """{"id":"login","action":"http:POST","resource":"http:path:/login","key":"environment.ip",""" +
        """"permitsPerSecond":0.5,"windowSeconds":60,"strictFailure":true}"""

    @Test
    fun aRuleIsReadAndWrittenBack() {
        val decoded = PolicyDocumentCodec.decode(DocumentKind.TENANT, "acme", body(rule))
        val read = decoded.rateLimits.single()
        read.id.assert().isEqualTo("login")
        read.key.assert().isEqualTo("environment.ip")
        read.limit.assert().isEqualTo(RateLimitSpec(0.5, 60))
        read.strictFailure.assert().isTrue()
        read.statement.actions.assert().containsExactly("http:POST")
        val again = PolicyDocumentCodec.decode(DocumentKind.TENANT, "acme", PolicyDocumentCodec.encode(decoded))
        PolicyDocumentCodec.encode(again).assert().isEqualTo(PolicyDocumentCodec.encode(decoded))
        // Defaults: one window of a second, lenient.
        val minimal = PolicyDocumentCodec.decode(
            DocumentKind.PLATFORM,
            "",
            body("""{"id":"m","action":"*","permitsPerSecond":3}""")
        )
        minimal.rateLimits.single().limit.assert().isEqualTo(RateLimitSpec(3.0, 1))
        minimal.rateLimits.single().strictFailure.assert().isFalse()
    }

    @Test
    fun anAppOrTenantRuleCountsInItsOwnBuckets() {
        compiler.compile(
            SnapshotKey.Tenant("acme"),
            stored(rule)
        ).rateLimits.single().id.assert().isEqualTo("tenant:acme/login")
        compiler.compile(SnapshotKey.Platform, stored(rule)).rateLimits.single().id.assert().isEqualTo("login")
    }

    @Test
    fun aMalformedRuleDoesNotDecodeOrCompile() {
        assertThrows<SnapshotFormatException> {
            PolicyDocumentCodec.decode(
                DocumentKind.PLATFORM,
                "",
                body("""{"id":"x","action":"*","permitsPerSecond":0}""")
            )
        }
        assertThrows<SnapshotFormatException> {
            PolicyDocumentCodec.decode(
                DocumentKind.PLATFORM,
                "",
                body("""{"id":"x","action":"*","permitsPerSecond":1,"bucket":"a"}""")
            )
        }
        val error = assertThrows<DocumentCompileException> {
            compiler.compile(
                SnapshotKey.Platform,
                stored("""{"id":"x","action":"*","permitsPerSecond":1,"key":"subject.authenticated"}""")
            )
        }
        error.errors.single().path.assert().isEqualTo("$.rateLimits[0].key")
    }
}
