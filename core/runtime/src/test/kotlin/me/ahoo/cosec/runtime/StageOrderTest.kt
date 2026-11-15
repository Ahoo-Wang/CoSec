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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.runtime

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * RFC 0013 §4.2: the request path is these stages, in this order. Within the deadline, a permit's obligations run
 * after the engine decided and before the outcome (OBL-1: a denied request never spends a permit). The identity, the
 * REQUIRED and the BEST_EFFORT listeners come after, on the outcome; their order is held by `IdentityForwardingTest`,
 * `ListenerIsolationTest` and `BreakGlassAndShadowTest`.
 */
class StageOrderTest {
    @Test
    fun `the request path is nine stages, in order`() {
        PipelineFixtures.pipeline().stages.map { it.javaClass.simpleName }.assert().containsExactly(
            "ReadRequest",
            "Authenticate",
            "ResolveMembers",
            "ResolveScope",
            "LoadView",
            "FetchAttributes",
            "Evaluate",
            "RunObligations",
            "Respond",
        )
    }
}
