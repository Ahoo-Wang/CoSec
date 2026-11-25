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
 * MIX-6 and MIX-7 between versions over one Redis: a revocation one version writes, the other reads, since one not read
 * is a token accepted again (the keys are frozen, RFC 0014 R6); an API key one version saves, the other reads alike.
 */
class CredentialCompatTest {
    @Test
    fun `a revocation either version writes, the other reads`() {
        for ((writer, reader) in PAIRS) {
            val prefix = prefix()
            writer("revoke.token", "prefix" to prefix, "id" to "t1")
            reader(
                "revoked.token",
                "prefix" to prefix,
                "id" to "t1"
            ).assert().describedAs("$writer -> $reader").isEqualTo("true")
            reader("revoked.token", "prefix" to prefix, "id" to "t2").assert().isEqualTo("false")
            writer("revoke.subject", "prefix" to prefix, "principal" to "alice", "before" to "2026-11-01T00:00:00Z")
            reader(
                "revoked.subject",
                "prefix" to prefix,
                "principal" to "alice"
            ).assert().describedAs("$writer -> $reader")
                .isEqualTo("2026-11-01T00:00:00Z")
        }
    }

    @Test
    fun `an API key either version saves, the other reads alike`() {
        for (sample in listOf("capped", "uncapped")) {
            for ((writer, reader) in PAIRS) {
                val prefix = prefix()
                writer("apikey.save", "prefix" to prefix, "hash" to "h1", "sample" to sample)
                val read = reader("apikey.read", "prefix" to prefix, "hash" to "h1")
                read.assert().describedAs("$writer -> $reader, $sample").startsWith("id=")
                read.assert().isEqualTo(Side.CURRENT("apikey.read", "prefix" to prefix, "hash" to "h1"))
            }
        }
    }
}
