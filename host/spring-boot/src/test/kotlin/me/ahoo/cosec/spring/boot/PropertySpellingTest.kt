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

package me.ahoo.cosec.spring.boot

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** The spelling a startup message suggests: only separators apart is exact, two edits is "did you mean", anything else is silence. */
class PropertySpellingTest {
    private val spelling = PropertySpelling(
        listOf(
            "cosec.pipeline.timeout",
            "cosec.authorization.snapshot.local-max-age",
            "cosec.aa.bbbbbbbbbb1",
            "cosec.aa.bbbbbbbbbb2"
        ),
    )

    @Test
    fun `keys that differ only in separators and case are the same spelling, indexes aside`() {
        val suggestion = spelling.suggest("cosec.authorization.snapshot.local.max.age[0]")!!
        suggestion.key.assert().isEqualTo("cosec.authorization.snapshot.local-max-age")
        suggestion.exact.assert().isTrue()
        suggestion.variable.assert().isEqualTo("COSEC_AUTHORIZATION_SNAPSHOT_LOCALMAXAGE")
        suggestion.toString().assert().isEqualTo(
            "write cosec.authorization.snapshot.local-max-age, or the variable COSEC_AUTHORIZATION_SNAPSHOT_LOCALMAXAGE",
        )
    }

    @Test
    fun `a typo within two edits is a guess`() {
        val suggestion = spelling.suggest("cosec.pipeline.timeuot")!!
        suggestion.exact.assert().isFalse()
        suggestion.toString().assert()
            .isEqualTo("did you mean cosec.pipeline.timeout, or the variable COSEC_PIPELINE_TIMEOUT?")
    }

    @Test
    fun `nothing is suggested for a short key, a far one, or when two keys are equally near`() {
        spelling.suggest("cosec.x").assert().isNull()
        spelling.suggest("cosec.nothing.like.any.key.at.all").assert().isNull()
        spelling.suggest("cosec.aa.bbbbbbbbbb3").assert().isNull()
    }

    @Test
    fun `two known keys with one spelling are never an exact answer`() {
        val ambiguous = PropertySpelling(listOf("cosec.some.key-name", "cosec.some.keyname"))
        ambiguous.suggest("cosec.some.key.name").assert().isNull()
    }
}
