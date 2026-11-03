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
        CookieHeaders.without("theme=dark; authorization=t", "authorization").assert().isEqualTo("theme=dark")
        CookieHeaders.without("a=1;Authorization=t;b=2", "authorization").assert().isEqualTo("a=1;b=2")
        CookieHeaders.without("a=1; AUTHORIZATION =t ; b=2", "authorization").assert().isEqualTo("a=1; b=2")
        CookieHeaders.without("authorization=\"t\"", "authorization").assert().isNull()
        CookieHeaders.without("authorization", "authorization").assert().isNull()
        CookieHeaders.without("authorization=t; authorization=u", "authorization").assert().isNull()
        CookieHeaders.without(" authorization=t ; ", "authorization").assert().isNull()
    }

    /**
     * R3-2 of #1194's review: each pair that goes takes one `;` with it, so no empty pair is made, which Python's
     * `SimpleCookie` would stop at, losing every cookie after it.
     */
    @Test
    fun `no empty pair is made`() {
        CookieHeaders.without("authorization=t; session=s", "authorization").assert().isEqualTo(" session=s")
        CookieHeaders.without("a=1; authorization=t; b=2", "authorization").assert().isEqualTo("a=1; b=2")
        // R3-6: a pair of whitespace and `,` alone holds no cookie; a header left with only such pairs is not sent.
        CookieHeaders.without("authorization=t;,", "authorization").assert().isNull()
        CookieHeaders.without("authorization=t; , ;\t", "authorization").assert().isNull()
        // An empty pair the header had stays: it is not this rewrite's to drop.
        CookieHeaders.without("a=1;;authorization=t;b=2", "authorization").assert().isEqualTo("a=1;;b=2")
    }

    /**
     * R2-6 of #1194's review: the pairs kept go on byte for byte, and Netty, which reads a header that starts with
     * `$Version` from just after its first `;`, reads every one as it did.
     */
    @Test
    fun `kept pairs go on as they came`() {
        CookieHeaders.without("a=1 ;authorization=t;  b=2", "authorization").assert().isEqualTo("a=1 ;  b=2")
        CookieHeaders.without(" \tAUTHORIZATION =t ; a=1", "authorization").assert().isEqualTo(" a=1")
        CookieHeaders.without(" \$Version=1; authorization=t; \$Path=/; a=b", "authorization")
            .assert().isEqualTo(" \$Version=1; \$Path=/; a=b")
        // A kept `$Version` pair that comes to the head gets a space, or Netty would read the header as RFC 2965.
        CookieHeaders.without("authorization=t;\$Version=1;a=b", "authorization").assert().isEqualTo(" \$Version=1;a=b")
        // Netty skips a leading `$Version` up to its `;`: that `;` stays, or `$Version` would decode as a cookie.
        CookieHeaders.without("\$Version=1;authorization=t", "authorization").assert().isEqualTo("\$Version=1;")
        CookieHeaders.without("\$Version=1;authorization=t;a=b", "authorization").assert().isEqualTo("\$Version=1;a=b")
        // A leading `$Version` pair that hides the token goes, and with it the whole header: Netty would read the rest
        // without RFC 2965, and decode `$Path` pairs it skipped.
        CookieHeaders.without("\$version=1, authorization=t;a=b;\$Path=/", "authorization").assert().isNull()
    }

    @Test
    fun `a pair that hides it behind a comma or a quote goes whole`() {
        CookieHeaders.without("a=1, authorization=t; b=2", "authorization").assert().isEqualTo(" b=2")
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
        PipelineFixtures.pipeline().withoutCookieToken("theme=dark; authorization=t").assert().isEqualTo("theme=dark")
    }
}
