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

package me.ahoo.cosec.architecture

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * RFC 0013 §8.1: a main source file has at most [SourceSizeLedger.LIMIT] lines, or an entry in [SourceSizeLedger] with
 * its cap and the batch that removes it or the reason it stays.
 */
class SourceSizeTest {
    private val sizes: Map<String, Int> = MainSources.files.associate { it.path to it.lines.size }
    private val entries = SourceSizeLedger.ENTRIES.associateBy { it.path }

    @Test
    fun `the ledger has one entry per file`() {
        SourceSizeLedger.ENTRIES.groupBy { it.path }.filterValues { it.size > 1 }.keys
            .assert().describedAs("files listed twice in SourceSizeLedger").isEmpty()
        sizes.assert().describedAs("no main sources: the rule would check nothing").isNotEmpty()
    }

    @Test
    fun `a main source file is at most the limit, or has an entry`() {
        val over = sizes.filter { (path, lines) -> lines > SourceSizeLedger.LIMIT && path !in entries }
            .map { (path, lines) -> "$path: $lines lines" }
        over.assert().describedAs(
            "main source files over ${SourceSizeLedger.LIMIT} lines: split the file (one type, or one family, per file), " +
                "or add an entry to SourceSizeLedger with its cap and its batch or the reason it stays, and have it reviewed",
        ).isEmpty()
    }

    @Test
    fun `a listed file stays within its cap`() {
        val grown = SourceSizeLedger.ENTRIES.mapNotNull { entry ->
            sizes[entry.path]?.takeIf { it > entry.cap }?.let { "${entry.path}: $it lines, cap ${entry.cap}" }
        }
        grown.assert().describedAs(
            "files over their cap in SourceSizeLedger: move the new code out of the file; a raised cap needs a review",
        ).isEmpty()
    }

    @Test
    fun `a cap follows its file down`() {
        val slack = SourceSizeLedger.ENTRIES.mapNotNull { entry ->
            val lines = sizes[entry.path] ?: return@mapNotNull null
            "${entry.path}: $lines lines, cap ${entry.cap}".takeIf {
                lines > SourceSizeLedger.LIMIT && entry.cap > lines * (1 + SourceSizeLedger.SLACK)
            }
        }
        slack.assert().describedAs(
            "caps more than 10 % above their file in SourceSizeLedger: lower the cap to the file's size, so the shrink stays",
        ).isEmpty()
    }

    @Test
    fun `every entry names a file that still needs one`() {
        val stale = SourceSizeLedger.ENTRIES.mapNotNull { entry ->
            when (val lines = sizes[entry.path]) {
                null -> "${entry.path}: no such main source file"
                in 0..SourceSizeLedger.LIMIT -> "${entry.path}: $lines lines, within the limit"
                else -> null
            }
        }
        stale.assert().describedAs("entries in SourceSizeLedger that are no longer needed: remove them").isEmpty()
    }
}
