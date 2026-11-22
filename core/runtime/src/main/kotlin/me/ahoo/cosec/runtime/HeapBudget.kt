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

package me.ahoo.cosec.runtime

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * Every share of the heap CoSec takes, in one place (`STA-12`; the rule of root `AGENTS.md`: what can be computed is not
 * configured). Each bound that holds memory for performance is a share of `Runtime.maxMemory()`, so a bigger pod holds
 * more and a smaller one less, visibly, instead of a count of entries that means another number of bytes on every heap.
 * Each share is chosen so that at the production overlay's 2 GiB heap the bound is not below the count it replaced.
 *
 * | Budget | Share | At 2 GiB |
 * |---|---|---|
 * | [revocationTables]: the local copies of the revocation tables (`IDN-33`) | 1/[REVOCATION_TABLES_SHARE] | 256 MiB |
 * | [tokenCache]: the verified-token cache (`IDN-32`) | 1/[TOKEN_CACHE_SHARE] | 128 MiB |
 * | [decisionListeners]: the BEST_EFFORT decision listeners' queues (`OBS-11`) | 1/[DECISION_LISTENERS_SHARE] | 32 MiB |
 * | [changeListeners]: the change listeners' queues (`CTL-66`) | 1/[CHANGE_LISTENERS_SHARE] | 64 MiB |
 * | [documents]: reserved for the snapshot documents, whose own bound is a later design | 1/[DOCUMENTS_SHARE] | 512 MiB |
 *
 * Together they take at most 1/[SUM_BOUND] of the heap ([SHARES]): the rest is the gateway's own and ZGC's headroom.
 */
@InternalCoSecApi
class HeapBudget private constructor(val heap: Long) {
    val revocationTables: Long get() = heap / REVOCATION_TABLES_SHARE
    val tokenCache: Long get() = heap / TOKEN_CACHE_SHARE
    val decisionListeners: Long get() = heap / DECISION_LISTENERS_SHARE
    val changeListeners: Long get() = heap / CHANGE_LISTENERS_SHARE
    val documents: Long get() = heap / DOCUMENTS_SHARE

    /** How many events one of [listeners] BEST_EFFORT decision listeners may have waiting: at least one. */
    fun decisionQueueCapacity(listeners: Int): Int =
        count(decisionListeners / listeners.coerceAtLeast(1), DECISION_EVENT_BYTES)

    override fun toString(): String =
        "heap ${mib(heap)} (Runtime.maxMemory()): revocation tables=${mib(revocationTables)} " +
            "(1/$REVOCATION_TABLES_SHARE), token cache=${mib(tokenCache)} (1/$TOKEN_CACHE_SHARE), " +
            "decision listener queues=${mib(decisionListeners)} (1/$DECISION_LISTENERS_SHARE), " +
            "change listener queues=${mib(changeListeners)} (1/$CHANGE_LISTENERS_SHARE), " +
            "reserved for documents=${mib(documents)} (1/$DOCUMENTS_SHARE)"

    @InternalCoSecApi
    companion object {
        /** 2 GiB / 8 = 256 MiB: 516,222 entries in each table at 290 + 230 bytes, where the bound was 500,000. */
        const val REVOCATION_TABLES_SHARE: Long = 8

        /** 2 GiB / 16 = 128 MiB: more than the 16,384 entries of 7.3 KB (about 115 MB) the bound was. */
        const val TOKEN_CACHE_SHARE: Long = 16

        /** 2 GiB / 64 = 32 MiB, shared by the BEST_EFFORT listeners, where each had 1,024 events. */
        const val DECISION_LISTENERS_SHARE: Long = 64

        /**
         * 2 GiB / 32 = 64 MiB (`CTL-66`, about 3 %). A control host's heap holds, besides, the compiled documents it
         * decides with, the CoCache local copies of the control store, the candidates of open shadow windows and the
         * requests in flight; a thirty-second leaves those their room and still holds tens of thousands of events.
         */
        const val CHANGE_LISTENERS_SHARE: Long = 32

        /** A quarter, reserved: the snapshot documents' bound (`max-apps`, `max-tenants`) is a later design. */
        const val DOCUMENTS_SHARE: Long = 4

        /** Every share together takes at most half the heap. */
        const val SUM_BOUND: Long = 2

        /** Every share, by name, for the sum ([SUM_BOUND]) and the docs. */
        val SHARES: Map<String, Long> = linkedMapOf(
            "revocation tables" to REVOCATION_TABLES_SHARE,
            "token cache" to TOKEN_CACHE_SHARE,
            "decision listener queues" to DECISION_LISTENERS_SHARE,
            "change listener queues" to CHANGE_LISTENERS_SHARE,
            "documents" to DOCUMENTS_SHARE,
        )

        /**
         * What one queued decision event retains, measured with JOL (`DecisionEventSizeTest` holds it, never below the
         * measurement and at most twice it): the queues count events with it, so nothing is measured per request.
         */
        const val DECISION_EVENT_BYTES: Long = 1_024

        private const val MIB = 1L shl 20

        fun of(heap: Long = Runtime.getRuntime().maxMemory()): HeapBudget {
            require(heap > 0) { "The heap must be positive, not $heap." }
            return HeapBudget(heap)
        }

        /** How many entries of [entryBytes] fit in [budget]: at least one, at most [Int.MAX_VALUE]. */
        fun count(budget: Long, entryBytes: Long): Int =
            (budget / entryBytes).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

        private fun mib(bytes: Long): String = "${bytes / MIB} MiB"
    }
}
