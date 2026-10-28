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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * OBL-3: every kind of obligation is handled, proved at compile time. The pipeline dispatches on the sealed `Obligation`
 * with an exhaustive `when` and no `else`, so a new kind does not compile until it is handled; nothing skips a kind it
 * does not know.
 */
class ObligationDispatchTest {
    private val main = Path.of("src", "main", "kotlin")

    @Test
    fun `obligations are dispatched by an exhaustive when without else`() {
        val pipeline = main.resolve("me/ahoo/cosec/runtime/Pipeline.kt").readText()
        val dispatch = Regex("""when \(next\) \{(.*?)\n\s*}""", RegexOption.DOT_MATCHES_ALL).find(pipeline)
        dispatch.assert().isNotNull()
        dispatch!!.groupValues[1].assert().contains("is Obligation.RateLimit").doesNotContain("else")
    }

    @Test
    fun `no runtime code skips a kind of obligation`() {
        val sources = Files.walk(main).use { paths -> paths.filter { it.extension == "kt" }.toList() }
        sources.assert().isNotEmpty()
        sources.filter { "!is Obligation." in it.readText() }.assert().isEmpty()
    }
}
