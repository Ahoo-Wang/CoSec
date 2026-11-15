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

package me.ahoo.cosec.api.token

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** A composite token is two credentials: equal by value, and printed without either. */
class CompositeTokenTest {
    @Test
    fun `its string names neither token`() {
        val printed = CompositeToken("access-secret", "refresh-secret").toString()
        printed.assert().doesNotContain("access-secret").doesNotContain("refresh-secret")
        "$printed".assert().isEqualTo("CompositeToken(accessToken=***, refreshToken=***)")
    }

    @Test
    fun `it is equal by value`() {
        val token = CompositeToken("a", "r")
        token.assert().isEqualTo(CompositeToken("a", "r")).hasSameHashCodeAs(CompositeToken("a", "r"))
        token.assert().isNotEqualTo(CompositeToken("a", "other")).isNotEqualTo(CompositeToken("other", "r"))
        token.equals("a").assert().isFalse()
    }
}
