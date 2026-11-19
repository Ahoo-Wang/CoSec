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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/** A source finds a family by its kind; a kind it holds no family of answers what the search by kind answered. */
class SnapshotFamilyLookupTest {
    private val store = ControllableStore().apply {
        put(SnapshotKey.Platform, document(1))
        put(APP_A, document(1))
    }

    @Test
    fun `each kind finds its own family, and a kind with none finds nothing`() {
        testSource(store).use { source ->
            source.families.of(SnapshotKind.APP)!!.spec.kind.assert().isEqualTo(SnapshotKind.APP)
            source.families.of(SnapshotKind.TENANT).assert().isNull()
            source.families.of(SnapshotKind.PLATFORM).assert().isNull()
        }
    }

    @Test
    fun `a key of no family throws as the search by kind did`() {
        testSource(store).use { source ->
            assertThrownBy<NoSuchElementException> { source.context.current(TENANT_A) }
                .hasMessageContaining("TENANT")
            assertThrownBy<NoSuchElementException> { source.loadsInFlight(SnapshotKind.TENANT) }
            assertThrownBy<IllegalArgumentException> { source.peekAll(listOf(TENANT_A)) }
        }
    }

    @Test
    fun `a change to another source's document is ignored`() {
        testSource(store).use { source ->
            source.load(APP_A.appId).join()
            store.notifyChanged(TENANT_A)
            source.peek(APP_A.appId).assert().isNotNull()
        }
    }
}
