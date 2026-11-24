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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/** DR1-E-16: an expiry of zero is refused when the options are made, at startup, naming its property. */
class ChangeOptionsTest {
    @Test
    fun `a change or an approval that expires at once is refused by name`() {
        assertThrownBy<IllegalArgumentException> { ChangeOptions(expireAfter = Duration.ZERO) }
            .hasMessageContaining("cosec.control.changes.expire-after")
        assertThrownBy<IllegalArgumentException> { ChangeOptions(approvalExpireAfter = Duration.ZERO) }
            .hasMessageContaining("cosec.control.approval.expire-after")
        assertThrownBy<IllegalArgumentException> { ChangeOptions(expireAfter = Duration.ofSeconds(-1)) }
        ChangeOptions(expireAfter = Duration.ofSeconds(1), approvalExpireAfter = Duration.ofSeconds(1))
            .expireAfter.assert().isEqualTo(Duration.ofSeconds(1))
    }
}
