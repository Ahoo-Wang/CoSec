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

package me.ahoo.cosec.token

import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.now
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

class RevocationReaderTest {
    @Test
    fun noneRevokesNothing() {
        now { RevocationReader.NONE.isRevoked("jti") }.assert().isFalse()
        now { RevocationReader.NONE.subjectRevokedBefore(TenantId("t"), PrincipalId("p")) }.assert().isNull()
    }

    /** RFC 0004 §4.5.3, the rule both token paths read. */
    @Test
    fun subjectRevocation() {
        val revokedBefore = Instant.parse("2026-10-27T00:00:00Z")
        SubjectRevocation.revokes(null, null).assert().isFalse()
        SubjectRevocation.revokes(null, revokedBefore).assert().isFalse()
        SubjectRevocation.revokes(revokedBefore, null).assert().isTrue()
        SubjectRevocation.revokes(revokedBefore, revokedBefore.minusMillis(1)).assert().isTrue()
        SubjectRevocation.revokes(revokedBefore, revokedBefore).assert().isFalse()
        SubjectRevocation.revokes(revokedBefore, revokedBefore.plusSeconds(1)).assert().isFalse()
    }
}
