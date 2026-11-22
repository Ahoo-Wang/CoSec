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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.CompiledDocument

/**
 * What a resident document retains on the heap, estimated from what it is made of, without measuring it (`SNP-24`): its
 * compiled form, its slot and what the slot keeps of it, and its revision kept across eviction. A snapshot family's
 * bound is the documents' share of the heap divided by these weights, so each is reckoned once, as the document is
 * compiled, never on a request.
 *
 * Fitted on measured documents (JOL, `DocumentWeightTest`, twelve shapes: tenants of roles only, of many role or
 * principal bindings, of policies with and without conditions, and apps of small to large catalogs): what a document
 * retains grows with the bindings its roles expand to, with its explicit statements, and with its stored size. The stored
 * size alone does not predict it (3.6 to 19.6 times, by shape); this estimate is never below what was
 * measured and at most 1.45 times it, which errs on the side of holding fewer documents than the share could.
 */
@InternalCoSecApi
object DocumentWeight {
    /** What any resident document retains, whatever it holds: its slot, its entry, its revision kept. */
    const val FIXED_BYTES: Long = 2_560

    /** Each binding, a role's grants expanded to bindings included (a typical tenant's 20 bindings of 5 roles are 120). */
    const val BINDING_BYTES: Long = 212

    /** Each explicit statement, with its patterns and its condition. */
    const val STATEMENT_BYTES: Long = 1_275

    /** Two and a half bytes per stored byte, as halves: the document the slot keeps for a recompile, and its strings. */
    const val STORED_HALF_BYTES: Long = 5

    /** What an entry for a document the store does not hold retains: 460 bytes measured, a fixed size (`SNP-7`). */
    const val ABSENT_ENTRY_BYTES: Long = 512

    /** The weight of a resident document compiled to [compiled] from [storedBytes] stored bytes. */
    fun of(compiled: CompiledDocument, storedBytes: Int): Long =
        FIXED_BYTES + BINDING_BYTES * compiled.bindingCount + STATEMENT_BYTES * compiled.statementCount +
            stored(storedBytes)

    /** The weight of a stored document that did not compile: it holds its failure and what the slot keeps of it. */
    fun ofUncompiled(storedBytes: Int): Long = FIXED_BYTES + stored(storedBytes)

    private fun stored(bytes: Int): Long = bytes.toLong() * STORED_HALF_BYTES / 2
}
