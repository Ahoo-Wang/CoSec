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

package me.ahoo.cosec.cocache.control

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * DR1-F-21: an open-change set's member, `<docKey>#<id>@<digest>`, names the change it was written for, whatever the
 * document and the id are. It is split at its last `#` and the first `@` after it, so no change id holds either, nor a
 * `/` or a `:`.
 */
class OpenChangeSetsParseTest {
    private val iterations = maxOf(20_000, Integer.getInteger("cosec.property.iterations", 0))

    @Test
    fun `a change id holds none of the characters a member or a path splits on`() {
        for (id in listOf("a#b", "#", "a@b", "@", "a/b", "/", "a:b")) {
            assertThrownBy<IllegalArgumentException> { ChangeId(id) }
        }
        ChangeId("0TEC7Hw00001zR0").value.assert().isEqualTo("0TEC7Hw00001zR0")
    }

    @Test
    fun `every member and cursor reads back as the change it was written for`() {
        val random = Random(SEED)
        var accepted = 0
        repeat(iterations) {
            val document = document(random)
            val id = runCatching { ChangeId(text(random, 1..12)) }.getOrNull() ?: return@repeat
            accepted++
            val digest = SnapshotDigest(random.nextBytes(32))
            val written = OpenChangeSets.member(document, id, digest)
            OpenChangeSets.parse(written).assert().describedAs(written).isEqualTo(document to id)
            OpenChangeSets.parse(OpenChangeSets.cursor(document, id)).assert().isEqualTo(document to id)
        }
        accepted.assert().describedAs("ids the generator made that ChangeId accepts").isGreaterThan(iterations / 10)
    }

    private fun document(random: Random): SnapshotKey = when (random.nextInt(3)) {
        0 -> SnapshotKey.Platform
        1 -> SnapshotKey.Tenant(owner(random))
        else -> SnapshotKey.App(owner(random))
    }

    /** An id a tenant or an app can have: `#` and `@` included, which no change id holds. */
    private fun owner(random: Random): String {
        while (true) {
            val id = text(random, 1..8)
            if (SnapshotKey.Tenant.isValidTenantId(id)) return id
        }
    }

    private fun text(random: Random, length: IntRange): String =
        String(CharArray(random.nextInt(length.first, length.last + 1)) { ALPHABET[random.nextInt(ALPHABET.length)] })

    private companion object {
        const val SEED = 2026_11_23L

        /** Letters and digits, and the characters the keys and members are made of. */
        const val ALPHABET = "aZ09-_.#@:/ %(*é"
    }
}
