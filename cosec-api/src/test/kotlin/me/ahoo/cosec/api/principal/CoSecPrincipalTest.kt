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

package me.ahoo.cosec.api.principal

import me.ahoo.cosec.api.principal.CoSecPrincipal.Companion.ANONYMOUS_ID
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class CoSecPrincipalTest {

    @Test
    fun anonymous() {
        val anonymous = object : CoSecPrincipal {
            override val id: String
                get() = ANONYMOUS_ID

            override fun getName(): String {
                TODO("Not yet implemented")
            }

            override val attrs: Map<String, Any>
                get() = TODO("Not yet implemented")
            override val policies: Set<String>
                get() = TODO("Not yet implemented")
            override val roles: Set<String>
                get() = TODO("Not yet implemented")
        }
        assertThat(anonymous.anonymous(), equalTo(true))
    }

    @Test
    fun authenticated() {
        val authenticated = object : CoSecPrincipal {
            override val id: String
                get() = "id"

            override fun getName(): String {
                TODO("Not yet implemented")
            }

            override val attrs: Map<String, Any>
                get() = TODO("Not yet implemented")
            override val policies: Set<String>
                get() = TODO("Not yet implemented")
            override val roles: Set<String>
                get() = TODO("Not yet implemented")
        }
        assertThat(authenticated.authenticated(), equalTo(true))
    }
}
