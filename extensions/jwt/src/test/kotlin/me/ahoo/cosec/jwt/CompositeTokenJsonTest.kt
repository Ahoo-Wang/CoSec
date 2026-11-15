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

package me.ahoo.cosec.jwt

import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * The body an application's login and refresh endpoints answer: a `CompositeToken` written by the mapper a Spring Boot
 * host has (the modules it finds, Kotlin's among them). Pinned to the byte, so a change of the class is not a change of
 * what clients read.
 */
class CompositeTokenJsonTest {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `a composite token is written and read as before`() {
        val token = CompositeToken("access.jwt", "refresh.jwt")
        mapper.writeValueAsString(
            token
        ).assert().isEqualTo("""{"accessToken":"access.jwt","refreshToken":"refresh.jwt"}""")
        val read = mapper.readValue(
            """{"accessToken":"access.jwt","refreshToken":"refresh.jwt"}""",
            CompositeToken::class.java
        )
        read.assert().isEqualTo(token)
    }
}
