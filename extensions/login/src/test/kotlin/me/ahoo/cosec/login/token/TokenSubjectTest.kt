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

package me.ahoo.cosec.login.token

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class TokenSubjectTest {
    /** Drill 2, finding 12: a host without apps names no app, as it names no tenant. */
    @Test
    fun `app and tenant default to none`() {
        val subject = TokenSubject("alice")
        subject.app.assert().isNull()
        subject.tenant.assert().isNull()
        subject.assert().isEqualTo(TokenSubject(id = "alice", app = null, tenant = null))
    }

    @Test
    fun `a subject equals one with the same fields, and says them`() {
        val subject = TokenSubject("alice", app = "shop", roles = setOf("clerk"))
        subject.assert().isEqualTo(TokenSubject("alice", "shop", null, setOf("clerk")))
        subject.hashCode().assert().isEqualTo(TokenSubject("alice", "shop", null, setOf("clerk")).hashCode())
        subject.assert().isNotEqualTo(TokenSubject("alice"))
        subject.toString().assert().contains("alice").contains("shop").contains("clerk")
    }
}
