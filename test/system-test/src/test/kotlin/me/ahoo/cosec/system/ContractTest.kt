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

package me.ahoo.cosec.system

import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path

class ContractTest {
    private val root: Path = SystemTopology.contract

    private fun answer(status: Int, body: String, requestId: String = "r-1", type: String = "application/problem+json") =
        Exchange(
            "GET",
            URI.create("http://gateway/x"),
            status,
            mapOf("CoSec-Request-Id" to listOf(requestId), "Content-Type" to listOf(type)),
            body
        )

    private val unauthorized =
        """{"type":"https://cosec.ahoo.me/problems/unauthorized","title":"Unauthorized","status":401,"code":"UNAUTHORIZED","requestId":"r-1"}"""

    @Test
    fun `an answer as the contract states passes, with or without a trace id`() {
        Contract.assertProblem(answer(401, unauthorized), "unauthorized", root)
        Contract.assertProblem(answer(401, unauthorized.replace("}", ""","traceId":"t"}""")), "unauthorized", root)
    }

    @Test
    fun `another status, media type, member or request id fails`() {
        assertThrownBy<AssertionError> { Contract.assertProblem(answer(403, unauthorized), "unauthorized", root) }
        assertThrownBy<AssertionError> {
            Contract.assertProblem(
                answer(401, unauthorized, type = "application/json"),
                "unauthorized",
                root
            )
        }
        assertThrownBy<AssertionError> {
            Contract.assertProblem(answer(401, unauthorized.replace("}", ""","detail":"x"}""")), "unauthorized", root)
        }
        assertThrownBy<AssertionError> {
            Contract.assertProblem(
                answer(401, unauthorized, requestId = "r-2"),
                "unauthorized",
                root
            )
        }
    }
}
