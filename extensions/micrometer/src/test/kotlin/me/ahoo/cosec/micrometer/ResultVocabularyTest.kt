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

package me.ahoo.cosec.micrometer

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.cosec.runtime.event.OutcomeSummary.Reason
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * UD-7: the `result` tag is the decision event's reason, word for word in this tag's case, for every reason there is,
 * as audit's decision is. Only the ways a request is undecided before the engine ran fold into `indeterminate` (the
 * `cause` tag says which), so `explicit_deny` counts only requests a DENY statement refused.
 */
class ResultVocabularyTest {
    @Test
    fun `every reason has the result of its own name`() {
        val listener = MetricsListener(SimpleMeterRegistry())
        val folded = setOf(Reason.AUTHENTICATOR_UNAVAILABLE, Reason.DEADLINE_EXCEEDED)
        Reason.entries.forEach { reason ->
            val expected = if (reason in folded) "indeterminate" else reason.name.lowercase()
            listener.result(reason).assert().describedAs(reason.name).isEqualTo(expected)
        }
    }
}
