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

package me.ahoo.cosec.jwt

import com.auth0.jwt.algorithms.Algorithm
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/** UA-6: java-jwt's `none` accepts any unsigned token, which CoSec would take for its own (break-glass included). */
class UnsignedAlgorithmTest {
    @Test
    fun `the verifier refuses Algorithm none`() {
        assertThrownBy<IllegalArgumentException> { JwtTokenVerifier(Algorithm.none()) }
            .hasMessageContaining("CoSec tokens are signed")
    }

    @Test
    fun `the converter refuses Algorithm none`() {
        assertThrownBy<IllegalArgumentException> { JwtTokenConverter(JwtFixture.IDS, Algorithm.none()) }
            .hasMessageContaining("Algorithm.none()")
    }

    @Test
    fun `a signing key of the library's none is refused, whatever the way in`() {
        assertThrownBy<IllegalArgumentException> { JwtSigning.of(Algorithm.none()) }
            .hasMessageContaining("CoSec tokens are signed")
    }

    @Test
    fun `a signing algorithm is accepted`() {
        JwtTokenVerifier(JwtFixture.SIGNING)
        JwtTokenConverter(JwtFixture.IDS, JwtFixture.SIGNING)
    }
}
