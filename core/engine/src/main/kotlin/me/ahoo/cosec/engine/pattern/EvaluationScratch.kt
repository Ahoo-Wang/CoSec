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

package me.ahoo.cosec.engine.pattern

/**
 * Scratch memory of one evaluation, made when the evaluation first needs it and then reused: the table
 * [SegmentGlob.table] works in, and the marks of the repeated targets each pass has evaluated (`ALG-9`). An evaluation
 * that needs neither makes none, so it adds nothing to the hot path. Not thread-safe: one evaluation owns it.
 */
internal class EvaluationScratch {
    private var words: LongArray? = null

    // Every statement matches the same segment values of the request's path: the last one's answer is kept.
    private var scanned: String? = null
    private var pairs = false

    /** The evaluator's marks of repeated targets, two bits per slot; null until a pass reaches one. */
    var marks: LongArray? = null

    /** At least [size] words, with unspecified contents. */
    fun words(size: Int): LongArray {
        val current = words
        if (current != null && current.size >= size) {
            return current
        }
        return LongArray(maxOf(size, (current?.size ?: 0) * 2)).also { words = it }
    }

    /** Whether [value] holds a surrogate pair. */
    fun hasPairs(value: String): Boolean {
        if (value !== scanned) {
            pairs = false
            for (position in 1 until value.length) {
                if (value[position - 1].isHighSurrogate() && value[position].isLowSurrogate()) {
                    pairs = true
                    break
                }
            }
            scanned = value
        }
        return pairs
    }
}

/** Gives an evaluation's [EvaluationScratch], making it on first use; the evaluator itself, so it allocates nothing. */
internal fun interface ScratchSource {
    fun scratch(): EvaluationScratch
}
