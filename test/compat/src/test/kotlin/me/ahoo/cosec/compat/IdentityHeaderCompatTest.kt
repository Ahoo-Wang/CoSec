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

package me.ahoo.cosec.compat

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * MIX-1, the `CoSec-Identity` header between versions (RFC 0014 R1, R2; IDN-41, IDN-42): what one version's gateway
 * writes, the other's service reads as the current reader does; a missing member stays missing; a member a later
 * version adds is ignored, unless `crit` names it, which a reader that does not know it refuses with 503. Both writers
 * state `auth.kind` (`IDN-44`), and both readers read it.
 */
class IdentityHeaderCompatTest {
    private val samples = listOf("person", "workload", "session", "no-memberships")

    private fun read(side: Side, header: String) = side("identity.read", "header" to header)

    @Test
    fun `each version reads what the other writes as the current reader does`() {
        for (sample in samples) {
            for ((writer, reader) in PAIRS) {
                val header = writer("identity.write", "sample" to sample)
                read(reader, header).assert().describedAs("$writer -> $reader, $sample")
                    .isEqualTo(read(Side.CURRENT, header))
                read(reader, header).assert().describedAs("$writer -> $reader, $sample").doesNotStartWith("refused:")
            }
        }
    }

    /** `IDN-44`: whose a credential is, as one version's writer states it in `auth.kind`, the other version reads alike. */
    @Test
    fun `each version reads the kind the other's writer states`() {
        mapOf("person" to "person", "session" to "person", "no-memberships" to "person", "workload" to "workload")
            .forEach { (sample, kind) ->
                for ((writer, reader) in PAIRS) {
                    reader("identity.kind", "header" to writer("identity.write", "sample" to sample))
                        .assert().describedAs("$writer -> $reader, $sample").isEqualTo(kind)
                }
            }
    }

    /**
     * `IDN-44`: a header that states no `auth.kind`, or a kind neither version knows, is a workload's to both readers,
     * never a person's and never refused: an application that writes its own header, or a value a later version adds,
     * reads as the kind that can do less. A kind that is not a string is malformed to both (400).
     */
    @Test
    fun `a header without a kind, or with one neither version knows, is a workload's to both readers`() {
        for (sample in listOf("person", "session")) {
            val json = HeaderJson.of(Side.CURRENT("identity.write", "sample" to sample))
            for ((what, value) in listOf("no kind" to null, "an unknown kind" to "\"robot\"")) {
                val header = HeaderJson.header(HeaderJson.withKind(json, value))
                for (reader in listOf(Side.CURRENT, Side.PREVIOUS)) {
                    read(reader, header).assert().describedAs("$reader, $sample, $what").doesNotStartWith("refused:")
                    reader("identity.kind", "header" to header).assert().describedAs("$reader, $sample, $what")
                        .isEqualTo("workload")
                }
            }
            val malformed = HeaderJson.header(HeaderJson.withKind(json, "1"))
            for (reader in listOf(Side.CURRENT, Side.PREVIOUS)) {
                read(reader, malformed).assert().describedAs("$reader, $sample, a kind that is not a string")
                    .isEqualTo("refused:identity:MALFORMED")
            }
        }
    }

    @Test
    fun `a header written without memberships is read without them, never as empty ones`() {
        for ((writer, reader) in PAIRS) {
            read(reader, writer("identity.write", "sample" to "no-memberships")).assert()
                .describedAs("$writer -> $reader").endsWith(";memberships=null")
        }
    }

    @Test
    fun `a member a later version adds is ignored by every reader, which reads the header it knows`() {
        val known = Side.CURRENT("identity.write", "sample" to "person")
        val later = HeaderJson.header(HeaderJson.adding(HeaderJson.of(known), "\"device\":\"d1\""))
        for (side in listOf(Side.CURRENT, Side.PREVIOUS)) {
            read(side, later).assert().describedAs("$side").isEqualTo(read(Side.CURRENT, known))
        }
    }

    @Test
    fun `a later member crit names is refused as an outage by a reader that does not know it`() {
        val known = Side.CURRENT("identity.write", "sample" to "person")
        val later = HeaderJson.header(
            HeaderJson.adding(HeaderJson.of(known), "\"device\":\"d1\"", "\"crit\":[\"device\"]")
        )
        for (side in listOf(Side.CURRENT, Side.PREVIOUS)) {
            read(side, later).assert().describedAs("$side").isEqualTo("refused:identity:UNSUPPORTED_MEMBER")
        }
    }
}
