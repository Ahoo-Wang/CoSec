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
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** R1-4 of #1194's review: what a gateway removes from a `Cookie` header so that no token the pipeline reads goes on. */
class CookieHeadersTest {
    @Test
    fun `the authorization cookie goes, however its name is cased or spaced`() {
        CookieHeaders.without("authorization=t; theme=dark", "authorization").assert().isEqualTo("theme=dark")
        CookieHeaders.without("a=1;Authorization=t;b=2", "authorization").assert().isEqualTo("a=1; b=2")
        CookieHeaders.without(" \tAUTHORIZATION =t ; a=1", "authorization").assert().isEqualTo("a=1")
        // What a decoder reads as part of a kept value stays: here the space before the `;`.
        CookieHeaders.without("a=1 ;authorization=t;  b=2", "authorization").assert().isEqualTo("a=1 ; b=2")
        CookieHeaders.without("authorization=\"t\"", "authorization").assert().isNull()
        CookieHeaders.without("authorization", "authorization").assert().isNull()
        CookieHeaders.without("authorization=t; authorization=u", "authorization").assert().isNull()
    }

    @Test
    fun `a pair that hides it behind a comma or a quote goes whole`() {
        CookieHeaders.without("a=1, authorization=t; b=2", "authorization").assert().isEqualTo("b=2")
        CookieHeaders.without("a=\"x;authorization=t\"; b=2", "authorization").assert().isEqualTo("a=\"x; b=2")
    }

    @Test
    fun `a header without it is the same header`() {
        val header = "a=1;b=2"
        CookieHeaders.without(header, "authorization").assert().isSameAs(header)
        CookieHeaders.without("authorizations=1; xauthorization=2", "authorization")
            .assert().isEqualTo("authorizations=1; xauthorization=2")
        CookieHeaders.without("a=authorization", "authorization").assert().isEqualTo("a=authorization")
    }

    @Test
    fun `the pipeline removes the cookie it reads a token from`() {
        PipelineFixtures.pipeline().withoutCookieToken("authorization=t; theme=dark").assert().isEqualTo("theme=dark")
    }
}
