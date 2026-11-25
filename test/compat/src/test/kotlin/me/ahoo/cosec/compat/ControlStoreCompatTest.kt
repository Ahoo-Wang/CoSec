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
 * MIX-15, a document's revision index and the app list between two control planes of different versions over one
 * Redis, through each version's own control store: read alike, rewritten unchanged, a later member dropped by a
 * rewrite that leaves the same record, and one `crit` names refused by a version that does not know it.
 */
class ControlStoreCompatTest {
    private val digest = "SHA-256:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

    /** A record of each kind, every member set: its cache, its key, its JSON, and the operations that read and rewrite it. */
    private class Record(val cache: String, val key: String, val json: String, val read: String, val rewrite: String)

    private val records = listOf(
        Record(
            "RevisionIndexCache", "tenant:acme",
            """{"format":1,"entries":[{"revision":2,"digest":"$digest","publishedAt":"2026-11-01T00:00:00Z","parent":"$digest","size":10}],""" +
                """"pending":[{"digest":"$digest","since":"2026-11-01T00:00:00Z"}],"floor":2,"reclaiming":["$digest"],""" +
                """"indexed":[{"target":"app:order","policy":"perm:order/read","revision":3,"own":true}]}""",
            "control.index.read", "control.index.rewrite",
        ),
        Record("ControlAppListCache", "all", """{"format":1,"apps":["order"],"complete":true}""", "control.apps.read", "control.apps.rewrite"),
    )

    private fun put(prefix: String, record: Record, json: String = record.json) =
        Side.CURRENT("raw.put", "prefix" to prefix, "cache" to record.cache, "key" to record.key, "value" to json)

    private fun read(side: Side, prefix: String, record: Record) = side(record.read, "prefix" to prefix, "key" to record.key)

    @Test
    fun `each version reads the record alike, and reads it again after the other rewrote it`() {
        for (record in records) {
            for ((writer, rewriter) in PAIRS) {
                val prefix = prefix()
                put(prefix, record)
                val expected = read(Side.CURRENT, prefix, record)
                expected.assert().describedAs(record.cache).doesNotStartWith("refused:").isNotEqualTo("none")
                read(writer, prefix, record).assert().describedAs("$writer, ${record.cache}").isEqualTo(expected)
                rewriter(record.rewrite, "prefix" to prefix, "key" to record.key).assert().describedAs("$rewriter").isEqualTo("true")
                read(writer, prefix, record).assert().describedAs("$writer after $rewriter, ${record.cache}").isEqualTo(expected)
            }
        }
    }

    @Test
    fun `a later member without crit is dropped by a rewrite, which leaves the same record`() {
        for (record in records) {
            for (rewriter in listOf(Side.PREVIOUS, Side.CURRENT)) {
                val prefix = prefix()
                put(prefix, record)
                val expected = read(Side.CURRENT, prefix, record)
                put(prefix, record, HeaderJson.adding(record.json, "\"hint\":1"))
                rewriter(record.rewrite, "prefix" to prefix, "key" to record.key).assert().isEqualTo("true")
                read(Side.CURRENT, prefix, record).assert().describedAs("$rewriter, ${record.cache}").isEqualTo(expected)
            }
        }
    }

    @Test
    fun `a record whose crit names a member a version does not know is refused by it, and never rewritten`() {
        for (record in records) {
            val prefix = prefix()
            val later = HeaderJson.adding(record.json, "\"device\":\"d1\"", "\"crit\":[\"device\"]")
            put(prefix, record, later)
            Side.CURRENT(record.rewrite, "prefix" to prefix, "key" to record.key).assert().describedAs(record.cache)
                .isEqualTo("refused:format")
            Side.CURRENT("raw.get", "prefix" to prefix, "cache" to record.cache, "key" to record.key).assert().isEqualTo(later)
            Baseline.from("8.0.0-beta.9", "the previous control store refuses a ${record.cache} record crit names a member of") {
                Side.PREVIOUS(record.rewrite, "prefix" to prefix, "key" to record.key).assert().isEqualTo("refused:format")
                Side.CURRENT("raw.get", "prefix" to prefix, "cache" to record.cache, "key" to record.key).assert().isEqualTo(later)
            }
        }
    }
}
