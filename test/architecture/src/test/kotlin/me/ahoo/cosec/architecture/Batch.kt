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

/**
 * The batches of RFC 0013 §8.2 that remove a structure debt: B1 the request path's stages, B2a and B2b the snapshot
 * source, B3 the evaluator, B4 the control plane, B5 the document codec by section, B6 the migration commands, B7 one
 * type per file where no other batch goes and the large files that need only an extraction.
 */
@Suppress("EnumNaming") // the batches' own names, as RFC 0013 writes them
enum class Batch {
    B1,
    B2a,
    B2b,
    B3,
    B4,
    B5,
    B6,
    B7,
    ;

    companion object {
        /**
         * The batches that have landed. The last PR of a batch adds it here, and [LandedBatchTest] then fails on every
         * ledger entry or suppression that still names it: that PR is where the batch is checked complete.
         */
        val LANDED: Set<Batch> = setOf(B1, B3, B7)
    }
}
