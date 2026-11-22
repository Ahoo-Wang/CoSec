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

package me.ahoo.cosec.spring.boot.pipeline

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.RevokedSubjectCache
import me.ahoo.cosec.cocache.RevokedTokenCache
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.jwt.VerifiedTokenCache
import me.ahoo.cosec.runtime.HeapBudget
import me.ahoo.cosec.runtime.snapshot.DocumentBudget
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment

/**
 * The memory bounds the starter derives from the heap (`STA-12`; the rule of root `AGENTS.md`: what can be computed is
 * not configured), and the part of the startup line that says what each came to and from what. The entry sizes are
 * `const` values, inlined here: this object loads whether or not `cosec-cocache` and `cosec-jwt` are on the classpath.
 */
internal object DerivedHeap {
    /** What one entry of each revocation table retains, both tables holding the same count (`IDN-33`). */
    private const val REVOCATION_ENTRY_BYTES = RevokedTokenCache.ENTRY_BYTES + RevokedSubjectCache.ENTRY_BYTES

    fun budget(): HeapBudget = HeapBudget.of()

    /** `cosec.pipeline.trace-level`, which says how large a queued decision event is (`OBS-11`). */
    fun traceLevel(environment: Environment): TraceLevel =
        Binder.get(environment).bind(PipelineProperties.PREFIX + ".trace-level", TraceLevel::class.java)
            .orElseGet { PipelineProperties().traceLevel }

    /** The entries each revocation table's local copy holds: the tables' share counted in pairs of entries. */
    fun revocationEntries(budget: HeapBudget): Long =
        HeapBudget.count(budget.revocationTables, REVOCATION_ENTRY_BYTES).toLong()

    /**
     * The heap's part of the startup line; [queueCapacity] is each BEST_EFFORT decision listener's, when there are any,
     * counted at the size of an event of [traceLevel].
     */
    fun line(budget: HeapBudget, queueCapacity: Int?, traceLevel: TraceLevel): String {
        val queues = queueCapacity?.let {
            ", decision listener queues=$it events each (${HeapBudget.decisionEventBytes(traceLevel)} B an event " +
                "at trace-level ${traceLevel.name.lowercase()})"
        }
        return "heap budget ($budget): ${DocumentBudget(budget.documents)}, recomputed each revalidation pass from " +
            "what is resident (cosec.snapshot.bound); revocation tables=${revocationEntries(budget)} entries each " +
            "(${RevokedTokenCache.ENTRY_BYTES} + ${RevokedSubjectCache.ENTRY_BYTES} B a pair), token cache weighed at " +
            "${VerifiedTokenCache.BYTES_PER_TOKEN_CHAR} B a token character${queues.orEmpty()}"
    }
}
