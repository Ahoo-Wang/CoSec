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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** PIP-20: the path within the application, whichever way the server writes the context path. */
class ApplicationPathsTest {
    @Test
    fun withoutAContextPathTheUriIsThePath() {
        ApplicationPaths.within("/orders/1", "").assert().isEqualTo("/orders/1")
    }

    @Test
    fun aContextPathAsTheUriWritesItIsRemoved() {
        ApplicationPaths.within("/app/orders/1", "/app").assert().isEqualTo("/orders/1")
        ApplicationPaths.within("/%61pp/orders/1", "/%61pp").assert().isEqualTo("/orders/1")
        ApplicationPaths.within("/app", "/app").assert().isEqualTo("")
    }

    /** Jetty writes the context path canonically while the URI keeps the client's form. */
    @Test
    fun aContextPathWrittenOtherwiseIsRemovedSegmentBySegment() {
        ApplicationPaths.within("/%61pp/orders/1", "/app").assert().isEqualTo("/orders/1")
        ApplicationPaths.within("/app;x=1/orders/%31", "/app").assert().isEqualTo("/orders/%31")
        ApplicationPaths.within("/a%2Fb/orders", "/a%2Fb").assert().isEqualTo("/orders")
        ApplicationPaths.within("/%61pp", "/app").assert().isEqualTo("")
    }

    @Test
    fun aUriThatDoesNotAgreeWithTheContextPathIsUnreadable() {
        ApplicationPaths.within("/apple/orders", "/app").assert().isEqualTo(ApplicationPaths.UNREADABLE)
        ApplicationPaths.within("/other/orders", "/app").assert().isEqualTo(ApplicationPaths.UNREADABLE)
        ApplicationPaths.within("/%zz/orders", "/app").assert().isEqualTo(ApplicationPaths.UNREADABLE)
        ApplicationPaths.within("/", "/app/v1").assert().isEqualTo(ApplicationPaths.UNREADABLE)
        PathNormalizer.normalize(ApplicationPaths.UNREADABLE).assert().isNull()
    }
}
