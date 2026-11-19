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

import me.ahoo.test.asserts.assert
import java.nio.file.Files
import java.nio.file.Path

/**
 * The problem types of `contract/problems/` (PIP-25): what a client relies on, held to what a deployed process answers.
 * Compared as the contract says: the status, the media type, every body member with no member missing and none extra but
 * the optional ones, and the request id equal in the header and the body.
 */
object Contract {
    @Suppress("UNCHECKED_CAST")
    fun problem(slug: String, root: Path = SystemTopology.contract): Map<String, Any?> =
        Json.mapper.readValue(
            Files.readString(root.resolve("problems/$slug.json")),
            Map::class.java
        ) as Map<String, Any?>

    /** Fails unless [exchange] is the problem [slug] as `contract/problems/<slug>.json` states it. */
    @Suppress("UNCHECKED_CAST")
    fun assertProblem(exchange: Exchange, slug: String, root: Path = SystemTopology.contract) {
        val contract = problem(slug, root)
        exchange.status.assert().describedAs("status of $exchange").isEqualTo(contract["status"])
        exchange.header("Content-Type").orEmpty().assert().describedAs("media type of $exchange")
            .startsWith(contract.headersOf()["Content-Type"])
        val requestId = exchange.header("CoSec-Request-Id")
        requestId.assert().describedAs("CoSec-Request-Id of $exchange").isNotBlank()
        val body = exchange.json()
        val expected = contract["body"] as Map<String, Any?>
        val optional = (contract["optionalMembers"] as Map<String, Any?>? ?: emptyMap()).keys
        (body.keys - expected.keys - optional).assert().describedAs(
            "members beyond the contract in $exchange"
        ).isEmpty()
        for ((member, value) in expected) {
            val actual = body[member]
            if (member == "requestId") {
                actual.assert().describedAs("requestId of $exchange").isEqualTo(requestId)
            } else {
                actual.assert().describedAs("$member of $exchange").isEqualTo(value)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.headersOf(): Map<String, String> = this["headers"] as Map<String, String>
}
