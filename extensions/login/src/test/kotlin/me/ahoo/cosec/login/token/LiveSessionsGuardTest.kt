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

package me.ahoo.cosec.login.token

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * IDN-14, IDN-22 by structure: `cosec-login` issues from an earlier token in one place, [LiveSessions], which reads
 * its revocations strictly and its family before anything is issued. So no other main source of `cosec-login` moves a
 * family or starts one at an earlier login, and an `Authentication` that reads an earlier credential (a verified
 * token, the request's security) holds a [LiveSessions]: the next issuer someone writes cannot skip the check by
 * writing it again.
 */
class LiveSessionsGuardTest {
    private val sources: Map<String, String> = Files.walk(Path.of("src/main/kotlin")).use { paths ->
        paths.filter { it.name.endsWith(".kt") }.toList().associate { it.name to it.readText() }
    }

    @Test
    fun `only the live-session check rotates a family or starts one at an earlier login`() {
        sources.assert().isNotEmpty()
        val callers = sources.filter { (name, text) ->
            name != DEFINITION && ISSUING.any { it.containsMatchIn(text) }
        }.keys
        callers.assert().describedAs(
            "main sources that rotate a family or start one at an earlier login"
        ).containsExactly(CHECK)

        val readers = sources.filter { (name, text) ->
            name != CHECK && AUTHENTICATION.containsMatchIn(text) && EARLIER.any { it.containsMatchIn(text) }
        }
        readers.keys.assert().describedAs("authentications that read an earlier credential").isNotEmpty()
        readers.filterValues { "LiveSessions(" !in it }.keys.assert()
            .describedAs("authentications that read an earlier credential without the live-session check")
            .isEmpty()
    }

    private companion object {
        const val DEFINITION = "TokenFamilies.kt"
        const val CHECK = "LiveSessions.kt"
        val ISSUING = listOf(Regex("""\.rotate\("""), Regex("""\.startAt\("""), Regex("""\.requireLive\("""))
        val AUTHENTICATION = Regex(""":\s*Authentication<""")

        /** What an issuer reads an earlier credential with: a verifier, or the request the pipeline permitted. */
        val EARLIER = listOf(Regex("""\.verify\("""), Regex("""\.refresh\("""), Regex("""RequestSecurity\.of\("""))
    }
}
