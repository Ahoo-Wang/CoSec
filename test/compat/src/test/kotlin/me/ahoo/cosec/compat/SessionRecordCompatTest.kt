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
 * MIX-4, session records between versions over one Redis (RFC 0014 G5, SNP-27 to SNP-29): each version reads what
 * the other wrote; an older version's rewrite keeps every member it knows and drops a later member, leaving the same
 * session; a record whose `crit` names a member a version does not know, that version refuses, and never rewrites.
 */
class SessionRecordCompatTest {
    private val cache = "TokenFamilyCache"

    @Test
    fun `each version reads the session the other wrote`() {
        for ((writer, reader) in PAIRS) {
            val prefix = prefix()
            writer("session.create", "prefix" to prefix, "id" to "f1", "root" to "r1").assert().isEqualTo("true")
            reader("session.read", "prefix" to prefix, "id" to "f1").assert().describedAs("$writer -> $reader")
                .isEqualTo("id=f1;tenant=acme;principal=alice;generation=1;rotated=false;revoked=false;root=r1")
        }
    }

    @Test
    fun `a rewrite by either version keeps every member, and the other reads it`() {
        for ((writer, rewriter) in PAIRS) {
            val prefix = prefix()
            writer("session.create", "prefix" to prefix, "id" to "f1", "root" to "r1")
            rewriter("session.rotate", "prefix" to prefix, "id" to "f1").assert().isEqualTo("true")
            writer("session.read", "prefix" to prefix, "id" to "f1").assert().describedAs("$writer -> $rewriter")
                .isEqualTo("id=f1;tenant=acme;principal=alice;generation=2;rotated=true;revoked=false;root=r1")
        }
    }

    @Test
    fun `a later member without crit is dropped by a rewrite, which leaves the same session`() {
        for (rewriter in listOf(Side.PREVIOUS, Side.CURRENT)) {
            val prefix = prefix()
            Side.CURRENT("session.create", "prefix" to prefix, "id" to "f1", "root" to "r1")
            val record = Side.CURRENT("raw.get", "prefix" to prefix, "cache" to cache, "key" to "f1")
            Side.CURRENT(
                "raw.put",
                "prefix" to prefix,
                "cache" to cache,
                "key" to "f1",
                "value" to HeaderJson.adding(record, "\"hint\":1")
            )
            rewriter(
                "session.rotate",
                "prefix" to prefix,
                "id" to "f1"
            ).assert().describedAs("$rewriter").isEqualTo("true")
            Side.CURRENT("session.read", "prefix" to prefix, "id" to "f1").assert().describedAs("$rewriter")
                .isEqualTo("id=f1;tenant=acme;principal=alice;generation=2;rotated=true;revoked=false;root=r1")
        }
    }

    @Test
    fun `a record whose crit names a member a version does not know is refused by it, and never rewritten`() {
        val prefix = prefix()
        Side.CURRENT("session.create", "prefix" to prefix, "id" to "f1")
        val record = Side.CURRENT("raw.get", "prefix" to prefix, "cache" to cache, "key" to "f1")
        val later = HeaderJson.adding(record, "\"device\":\"d1\"", "\"crit\":[\"device\"]")
        Side.CURRENT("raw.put", "prefix" to prefix, "cache" to cache, "key" to "f1", "value" to later)
        Side.CURRENT("session.rotate", "prefix" to prefix, "id" to "f1").assert().isEqualTo("refused:format")
        Side.CURRENT("raw.get", "prefix" to prefix, "cache" to cache, "key" to "f1").assert().isEqualTo(later)
        Baseline.from("8.0.0-beta.9", "the previous version refuses a session record crit names a member of") {
            Side.PREVIOUS("session.rotate", "prefix" to prefix, "id" to "f1").assert().isEqualTo("refused:format")
            Side.CURRENT("raw.get", "prefix" to prefix, "cache" to cache, "key" to "f1").assert().isEqualTo(later)
        }
    }
}
