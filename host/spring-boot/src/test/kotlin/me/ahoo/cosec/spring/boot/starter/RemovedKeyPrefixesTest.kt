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

package me.ahoo.cosec.spring.boot.starter

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * `SNP-9`, review M3 of #1200: an environment written for the alpha's three key prefixes fails startup, whatever
 * `cosec.unknown-properties` says, and the message says which families move under `cosec.key-prefix`, from where to
 * where, and that the token revocations and API keys must be copied first. Renaming the property alone would leave
 * revocations where nothing reads them, and the tokens they revoked accepted again.
 */
class RemovedKeyPrefixesTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoSecPropertyCheckAutoConfiguration::class.java))

    private fun ApplicationContextRunner.failure(vararg properties: String): String? {
        var message: String? = null
        withPropertyValues(*properties).run { context ->
            message = context.startupFailure?.let { failure ->
                generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
            }
        }
        return message
    }

    @Test
    fun `a removed prefix fails startup even when unknown properties only warn`() {
        // RC1-46's deployment B, upgraded with its configuration unchanged: the moves are those of the prefix it must
        // set, its snapshot prefix, so the revocations are listed before anyone edits anything (round 2 of #1200, M1).
        val message = runner.failure("${RemovedKeyPrefixes.SNAPSHOT}=b", "cosec.unknown-properties=warn")!!
        message.assert().contains("CoSec no longer reads ${RemovedKeyPrefixes.SNAPSHOT}")
        message.assert().contains("cosec.key-prefix set to b, the snapshot documents' prefix, as it must be")
        message.assert().contains("token revocations and memberships: cosec: -> b:")
        message.assert().contains("API keys: cosec:api-key -> b:api-key")
        message.assert().contains("rate limit counters and the social login's OAuth states: cosec: -> b:")
        message.assert().doesNotContain("snapshot documents, control-plane records")
        message.assert().contains("cosec.unknown-properties does not turn this check off")
        message.assert().contains(RemovedKeyPrefixes.DOCUMENTATION)
    }

    @Test
    fun `the message names the families that move under the new prefix`() {
        // Deployment B once it sets cosec.key-prefix to its snapshot prefix, the old key still there: the same moves.
        val message = runner.failure("${RemovedKeyPrefixes.SNAPSHOT}=b", "cosec.key-prefix=b:")!!
        message.assert().contains("With the old values and cosec.key-prefix (b), these families move:")
        message.assert().doesNotContain("snapshot documents, control-plane records")
        message.assert().contains("token revocations and memberships: cosec: -> b:")
        message.assert().contains("API keys: cosec:api-key -> b:api-key")
        message.assert().contains("rate limit counters and the social login's OAuth states: cosec: -> b:")
        message.assert().contains("Copy the token revocations and the API keys under the new prefix")
        // Another prefix than the snapshot documents' moves them too, and the message says where they are.
        val elsewhere = runner.failure("${RemovedKeyPrefixes.SNAPSHOT}=b", "cosec.key-prefix=c")!!
        elsewhere.assert().contains(
            "snapshot documents, control-plane records, candidates and shadow reports: b: -> c:"
        )
        elsewhere.assert().contains("Set cosec.key-prefix to the snapshot documents' prefix (b).")
    }

    @Test
    fun `every removed prefix counts, by any of its names`() {
        runner.failure("${RemovedKeyPrefixes.CACHE}=c")!!
            .assert().contains("token revocations and memberships: c: -> cosec:")
        runner.failure("cosec.api-key.keyPrefix=x:keys")!!
            .assert().contains("API keys: x:keys -> cosec:api-key")
    }

    @Test
    fun `a removed prefix set to what the new one gives still fails, moving nothing`() {
        val message = runner.failure(
            "${RemovedKeyPrefixes.CACHE}=cosec",
            "${RemovedKeyPrefixes.API_KEY}=cosec:api-key:"
        )!!
        // A trailing `:` was ignored before as now, so `cosec:api-key:` is where the keys already are.
        message.assert().contains("no family moves")
        message.assert().contains("${RemovedKeyPrefixes.CACHE}, ${RemovedKeyPrefixes.API_KEY}")
    }

    @Test
    fun `cosec key-prefix alone starts`() {
        runner.failure("cosec.key-prefix=b").assert().isNull()
    }

    @Test
    fun `the removed prefixes name what replaced them`() {
        for (name in listOf(RemovedKeyPrefixes.SNAPSHOT, RemovedKeyPrefixes.CACHE, RemovedKeyPrefixes.API_KEY)) {
            UnknownProperties.hint(name)!!.assert().startsWith("cosec.key-prefix")
        }
    }
}
