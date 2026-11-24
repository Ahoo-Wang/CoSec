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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/** `IDN-38`: a stored change record whose actor is blank is a malformed record, never an unnamed error. */
class StoredActorTest {
    private fun actor(principal: String) =
        ChangeJson.actor(CoSecJson.mapper.readTree("""{"tenant":"acme","principal":"$principal"}"""))

    @Test
    fun `a blank actor is a malformed record`() {
        for (blank in listOf("", " ", "\\t")) {
            assertThrownBy<SnapshotFormatException> { actor(blank) }.message().contains("[principal] is blank")
        }
        actor(" alice ").principal.value.assert().isEqualTo(" alice ")
    }
}
