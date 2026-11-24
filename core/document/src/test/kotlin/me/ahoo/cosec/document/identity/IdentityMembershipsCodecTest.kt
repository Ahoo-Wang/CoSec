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

package me.ahoo.cosec.document.identity

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.identity.IdentityFormatException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.Base64

/**
 * `IDN-39`: `memberships` carries what `roles` does not, the principal's roles in each other space and its attachments,
 * read back as written; a header written is a header read, and what the reader refuses the writer refuses first, in the
 * same words.
 */
class IdentityMembershipsCodecTest {
    private companion object {
        const val WARM_UP = 20_000
        const val ENCODES = 10_000

        /** A few hundred bytes: the text and its copies, far below one node (or collection) per part. */
        const val TEXT_MARGIN = 384L
    }

    private val now = Instant.parse("2026-11-01T00:00:00Z")

    private fun identity(space: String = "team-a", memberships: Identity.Memberships? = Identity.Memberships.NONE) =
        Identity(
            tenant = "acme",
            subject = "alice",
            app = "order",
            space = space,
            roles = setOf("clerk"),
            credential = Identity.Credential(Identity.Method.JWT, "t1", now.plusSeconds(3600)),
            issuedAt = now,
            requestId = "r1",
            memberships = memberships,
        )

    private fun json(identity: Identity): String =
        String(Base64.getUrlDecoder().decode(IdentityCodec.encode(identity)), Charsets.UTF_8)

    private fun header(memberships: String, space: String = "team-a"): String {
        val json = """{"v":1,"tenant":"acme","sub":"alice","scope":{"app":"order","space":"$space"},"roles":["clerk"],""" +
            """"memberships":$memberships,"auth":{"method":"jwt","jti":"t1","exp":1793404800},"iat":1793404200,"rid":"r1"}"""
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    @Test
    fun `the other spaces and the attachments are read back as written, the default space under its empty key`() {
        val memberships = Identity.Memberships(
            mapOf("emea" to setOf("suspended", "auditor"), "" to setOf("reader")),
            listOf("suspended-from-publishing", "audited"),
        )
        val written = identity(memberships = memberships)

        IdentityCodec.decode(IdentityCodec.encode(written)).assert().isEqualTo(written)
        // Spaces sorted, each one's roles sorted; the attachments in their order, which reports follow.
        json(written).assert().contains(
            """"roles":["clerk"],"memberships":{"spaces":{"":["reader"],"emea":["auditor","suspended"]},""" +
                """"attachments":["suspended-from-publishing","audited"]},"auth":"""
        )
    }

    @Test
    fun `nothing more is written as empty, never left out, and read back as none`() {
        val written = identity()

        json(written).assert().contains(""""memberships":{"spaces":{},"attachments":[]}""")
        IdentityCodec.decode(IdentityCodec.encode(written)).memberships.assert().isSameAs(Identity.Memberships.NONE)
    }

    @Test
    fun `a writer that states no memberships writes no member, and the reader reads none`() {
        val written = identity(memberships = null)

        json(written).assert().doesNotContain("memberships")
        IdentityCodec.decode(IdentityCodec.encode(written)).memberships.assert().isNull()
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        textBlock = """
        {"spaces":{"team-a":["x"]},"attachments":[]}       | $.memberships.spaces["team-a"] is the identity's own space
        {"spaces":{"*":["x"]},"attachments":[]}            | $.memberships.spaces["*"] is the wildcard
        {"spaces":{"emea":[]},"attachments":[]}            | $.memberships.spaces["emea"] names no role
        {"spaces":{"emea":["x","x"]},"attachments":[]}     | $.memberships.spaces["emea"][1] repeats a role
        {"spaces":{"emea":[""]},"attachments":[]}          | $.memberships.spaces["emea"][0] is empty
        {"spaces":{"emea":"x"},"attachments":[]}           | $.memberships.spaces["emea"] is not an array
        {"spaces":[],"attachments":[]}                     | $.memberships.spaces is not an object
        {"spaces":{},"attachments":["p","p"]}              | $.memberships.attachments[1] repeats a policy
        {"spaces":{},"attachments":[""]}                   | $.memberships.attachments[0] is empty
        {"spaces":{},"attachments":{}}                     | $.memberships.attachments is not an array
        {"spaces":{}}                                      | [memberships.attachments] is missing
        {"attachments":[]}                                 | [memberships.spaces] is missing
        {"spaces":{},"attachments":[],"policies":[]}       | $.memberships has unknown members
        []                                                 | $.memberships is not an object""",
    )
    fun `memberships that are not this format are refused, never read as none`(memberships: String, reason: String) {
        val error = assertThrows<IdentityFormatException> { IdentityCodec.decode(header(memberships)) }
        error.reason.assert().isEqualTo(IdentityFormatException.Reason.MALFORMED)
        error.message.assert().contains(reason)
    }

    @Test
    fun `the own space is refused under memberships whichever space it is, the default one included`() {
        assertThrows<IdentityFormatException> {
            IdentityCodec.decode(header("""{"spaces":{"":["x"]},"attachments":[]}""", space = ""))
        }.message.assert().contains("""$.memberships.spaces[""] is the identity's own space""")
        IdentityCodec.decode(header("""{"spaces":{"":["x"]},"attachments":[]}""")).memberships!!.spaces
            .assert().isEqualTo(mapOf("" to setOf("x")))
    }

    @Test
    fun `a space with an unpaired surrogate is refused`() {
        // The JSON reader refuses it before the codec does; either way it is malformed, never read.
        assertThrows<IdentityFormatException> {
            IdentityCodec.decode(header("""{"spaces":{"e\ud800":["x"]},"attachments":[]}"""))
        }.reason.assert().isEqualTo(IdentityFormatException.Reason.MALFORMED)
    }

    @Test
    fun `what the reader refuses the writer refuses first, in the same words`() {
        val unwritable = listOf(
            Identity.Memberships(mapOf("team-a" to setOf("x")), emptyList()),
            Identity.Memberships(mapOf("*" to setOf("x")), emptyList()),
            Identity.Memberships(mapOf("emea" to emptySet()), emptyList()),
            Identity.Memberships(mapOf("emea" to setOf("")), emptyList()),
            Identity.Memberships(mapOf("e\uD800" to setOf("x")), emptyList()),
            Identity.Memberships(emptyMap(), listOf("p", "p")),
            Identity.Memberships(emptyMap(), listOf("")),
        )
        for (memberships in unwritable) {
            val written = assertThrows<IdentityFormatException> {
                IdentityCodec.encode(
                    identity(memberships = memberships)
                )
            }
            written.reason.assert().isEqualTo(IdentityFormatException.Reason.MALFORMED)
            written.message.assert().startsWith("The identity header is malformed: $.memberships.")
        }
    }

    /**
     * The gateway writes the identity on every request it forwards, and most state nothing more: `memberships` then costs
     * its text alone (31 bytes of JSON, 44 characters of base64, each copied by the encoder), never a node or a copy of
     * a collection. Measured as what the thread allocates per encode, beside the same identity without the member.
     */
    @Test
    fun `nothing more costs the encoder only its text, and two attachments only theirs`() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        fun perEncode(identity: Identity): Long {
            repeat(WARM_UP) { IdentityCodec.encode(identity) }
            val before = threads.currentThreadAllocatedBytes
            repeat(ENCODES) { IdentityCodec.encode(identity) }
            return (threads.currentThreadAllocatedBytes - before) / ENCODES
        }
        val without = perEncode(identity(memberships = null))
        val none = perEncode(identity())
        val two = perEncode(identity(memberships = Identity.Memberships(emptyMap(), listOf("principal-a", "principal-b"))))

        (none - without).assert().describedAs("NONE over no member: %d bytes", none - without).isLessThan(TEXT_MARGIN)
        (two - none).assert().describedAs("two attachments over NONE: %d bytes", two - none).isLessThan(TEXT_MARGIN)
    }

    @Test
    fun `too many memberships for the header are counted in the refusal, never cut`() {
        val many = Identity.Memberships((1..400).associate { "space-$it" to setOf("role-$it") }, emptyList())
        assertThrows<me.ahoo.cosec.spi.identity.IdentityOverflowException> {
            IdentityCodec.encode(identity(memberships = many))
        }.message.assert().contains("400 roles in 400 other spaces, 0 attachments")
    }
}
