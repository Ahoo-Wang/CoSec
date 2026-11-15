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
 * RFC 0013 §8.2: a batch that has landed ([Batch.LANDED]) leaves nothing behind. No entry of [SourceSizeLedger],
 * [TypeLedger] or [PackageLedger], and no `// B<n>:` line above a structure suppression, names it.
 */
class LandedBatchTest {
    private val batchLine = Regex("""^\s*// (\w+): """)

    private fun leftovers(): List<String> {
        val landed = Batch.LANDED
        val sizes = SourceSizeLedger.ENTRIES.mapNotNull { entry ->
            (entry.disposition as? Disposition.Debt)?.batch?.takeIf { it in landed }?.let { "$it: size of ${entry.path}" }
        }
        val types = TypeLedger.ENTRIES.mapNotNull { (path, disposition) ->
            (disposition as? Disposition.Debt)?.batch?.takeIf { it in landed }?.let { "$it: types of $path" }
        }
        val edges = PackageLedger.MODULES.flatMap { module ->
            module.debt.filter { it.batch in landed }.map { "${it.batch}: ${module.name} ${it.from} -> ${it.to}" }
        }
        val suppressions = DetektSuppressionTest.suppressions().mapNotNull { suppression ->
            val named = suppression.above?.let { batchLine.find(it)?.groupValues?.get(1) }
            landed.firstOrNull { it.name == named }?.let { "$it: ${suppression.path}:${suppression.line}" }
        }
        return sizes + types + edges + suppressions
    }

    @Test
    fun `nothing names a batch that has landed`() {
        leftovers().assert().describedAs(
            "entries and suppressions that name a landed batch (Batch.LANDED): the batch is not complete; finish it, " +
                "or move the entry to the batch that will",
        ).isEmpty()
    }
}
