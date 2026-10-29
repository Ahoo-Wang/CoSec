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

package me.ahoo.cosec.runtime.document

import me.ahoo.cosec.engine.flat.OnMissing
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/** RFC 0006 §5.4: documents read external attributes the catalog declares, and say what `onMissing` means. */
class ExternalAttributeDocumentTest {
    private val catalog = AttributeCatalog.of(listOf(Attribute.external("ext.geo.inFence", AttributeType.BOOL)))
    private val compiler = PolicyDocumentCompiler(catalog)

    private fun stored(body: String) = SnapshotDocument(
        2,
        1,
        Instant.parse("2026-10-26T00:00:00Z"),
        """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"$body}""".encodeToByteArray(),
    )

    private fun policies(effect: String, onMissing: String?, attribute: String = "ext.geo.inFence") =
        ""","policies":[{"id":"p","statements":[{"effect":"$effect","action":"*",""" +
            """"condition":{"eq":["$attribute",true]}${onMissing?.let { ""","onMissing":"$it"""" } ?: ""}}]}]"""

    private fun errors(compiler: PolicyDocumentCompiler, key: SnapshotKey, body: String): List<String> = try {
        compiler.compile(key, stored(body))
        emptyList()
    } catch (error: DocumentCompileException) {
        error.errors.map { "${it.path}: ${it.message}" }
    }

    @Test
    fun aDeclaredExternalAttributeCompiles() {
        errors(compiler, SnapshotKey.Tenant("acme"), policies("allow", "nomatch")).assert().isEmpty()
    }

    @Test
    fun anUndeclaredExternalAttributeDoesNotCompile() {
        errors(PolicyDocumentCompiler(), SnapshotKey.Tenant("acme"), policies("allow", null)).single()
            .assert().contains("not declared by any registered AttributeProvider")
        errors(compiler, SnapshotKey.Tenant("acme"), policies("allow", null, "ext.geo.other")).single()
            .assert().contains("ext.geo.other")
    }

    private val confirmed = ""","onMissingConfirmed":{"by":{"tenant":"(platform)","principal":"plato"},"change":"c1"}"""

    private fun confirmedDeny() =
        ""","policies":[{"id":"p","statements":[{"effect":"deny","action":"*",""" +
            """"condition":{"eq":["ext.geo.inFence",true]},"onMissing":"nomatch"$confirmed}]}]"""

    /** RFC 0007 §9.5 (N6, Q13): a DENY may lapse while its provider fails, in any document, only when confirmed. */
    @Test
    fun aLapsingDenyNeedsItsConfirmation() {
        errors(
            compiler,
            SnapshotKey.Tenant("acme"),
            policies("deny", "nomatch")
        ).single().assert().contains("confirmation")
        errors(compiler, SnapshotKey.Platform, policies("deny", "nomatch")).single().assert().contains("confirmation")
        errors(compiler, SnapshotKey.Tenant("acme"), confirmedDeny()).assert().isEmpty()
        errors(compiler, SnapshotKey.Platform, confirmedDeny()).assert().isEmpty()
        errors(compiler, SnapshotKey.Tenant("acme"), policies("deny", "indeterminate")).assert().isEmpty()
        val decoded = PolicyDocumentCodec.decode(DocumentKind.TENANT, "acme", stored(confirmedDeny()).bodyBytes())
        val confirmation = decoded.policies.single().statements.single().onMissingConfirmed!!
        confirmation.principal.assert().isEqualTo("plato")
        confirmation.change.assert().isEqualTo("c1")
        PolicyDocumentCodec.decode(DocumentKind.TENANT, "acme", PolicyDocumentCodec.encode(decoded))
            .policies.single().statements.single().onMissingConfirmed.assert().isEqualTo(confirmation)
    }

    @Test
    fun onMissingIsReadAndWrittenBack() {
        val decoded = PolicyDocumentCodec.decode(
            DocumentKind.TENANT,
            "acme",
            stored(policies("allow", "nomatch")).bodyBytes()
        )
        decoded.policies.single().statements.single().onMissing.assert().isEqualTo(OnMissing.NOMATCH)
        val again = PolicyDocumentCodec.decode(DocumentKind.TENANT, "acme", PolicyDocumentCodec.encode(decoded))
        again.policies.single().statements.single().onMissing.assert().isEqualTo(OnMissing.NOMATCH)
        val implicit = PolicyDocumentCodec.decode(
            DocumentKind.TENANT,
            "acme",
            stored(policies("allow", null)).bodyBytes()
        )
        implicit.policies.single().statements.single().onMissing.assert().isNull()
        assertThrows<SnapshotFormatException> {
            compiler.compile(SnapshotKey.Tenant("acme"), stored(policies("allow", "maybe")))
        }.message.assert().contains("onMissing")
    }
}
