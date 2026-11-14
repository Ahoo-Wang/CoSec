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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** Technology types that stay implementation details: they never appear in a public signature (RFC 0011 D3, §5). */
class JacksonSignatureTest {
    /**
     * D3: the contracts and the core carry no Jackson type, so a Jackson major upgrade is never a breaking change of
     * CoSec. A member CoSec's own modules need marks itself `@InternalCoSecApi` (D4).
     */
    @Test
    fun `the contracts and the core carry no Jackson type in a public signature`() {
        val checked = PublicSignatures.classes { Projects.groupOf(it) in setOf("api", "core") }
        // The rule sees the codec: a filter that let nothing through would pass vacuously.
        checked.map { it.name }.assert().contains("me.ahoo.cosec.runtime.document.PolicyDocumentCodec")
        PublicSignatures.violations(checked, Regex("""tools\.jackson\.""")).assert().isEmpty()
    }

    /**
     * The test kit is what applications write their policy tests against for the whole 8.x line (round 2 L9): its API
     * takes scenarios and documents as text and files, never Jackson's tree, so a Jackson major upgrade does not break
     * them. The conformance suite's tree-level entry points are `@InternalCoSecApi`.
     */
    @Test
    fun `the test kit carries no Jackson type in a public signature`() {
        val checked = PublicSignatures.classes { it == "testkit" }
        checked.map { it.name }.assert().contains("me.ahoo.cosec.testkit.PolicyTestKit")
        PublicSignatures.violations(checked, Regex("""tools\.jackson\.""")).assert().isEmpty()
    }

    /**
     * `cocache` reads Redis through Spring Data Redis's reactive API (the reviewed Reactor exception of
     * `SourceRulesTest`), and keeps it inside: its public API is the suspending SPIs, so no Reactor type reaches a caller.
     */
    @Test
    fun `cocache carries no Reactor type in a public signature`() {
        val checked = PublicSignatures.classes { it == "cocache" }
        checked.map { it.name }.assert().contains("me.ahoo.cosec.cocache.limiter.ReactiveRedisRateLimiter")
        PublicSignatures.violations(checked, Regex("""(?<![\w.])reactor\.""")).assert().isEmpty()
    }
}
