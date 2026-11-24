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

package me.ahoo.cosec.cocache

/** One golden sample of a [FrozenLayout] entry: a fixed input, the output 8.x keeps, and what the code makes of it now. */
internal class Golden(val entry: FrozenLayout, val sample: String, val expected: Any?, val actual: () -> Any?) {
    fun check() {
        val was = actual()
        if (was != expected) throw AssertionError(frozen(entry, sample, expected, was))
    }
}

/** The failure every frozen sample reports: what it is, why it is frozen, what to do instead. */
internal fun frozen(entry: FrozenLayout, sample: String, expected: Any?, was: Any?): String {
    val consequence = when (entry.ifChanged) {
        FrozenLayout.IfChanged.WIDENS -> "lets through what both releases refuse, or counts past what either allows"
        FrozenLayout.IfChanged.REFUSES -> "refuses, or cannot see, what both releases accept"
    }
    return "${entry.what.replaceFirstChar(Char::uppercaseChar)} is a format frozen within 8.x (RFC 0014 R6, SNP-26, " +
        "FrozenLayout.${entry.name}): instances of two releases read and write it on one Redis, and a mixed deployment " +
        "that derives it apart $consequence. Changing it needs a new key space, written once every reader can read it " +
        "(R5). Sample [$sample]: expected <$expected>, was <$was>."
}
