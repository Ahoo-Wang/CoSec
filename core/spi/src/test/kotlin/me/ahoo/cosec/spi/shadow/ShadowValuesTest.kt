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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.spi.shadow

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** RFC 0007 §6.4, §6.5: the shadow window's values are compared by value, and a candidate names its window. */
class ShadowValuesTest {
    private val at = Instant.parse("2026-11-01T00:00:00Z")
    private val document = SnapshotDocument(2, 5, at, """{"schema":2}""".encodeToByteArray())

    @Test
    fun `an installed document is its revision and its lag`() {
        val installed = InstalledDocument(4, Duration.ofMillis(250))
        installed.assert().isEqualTo(InstalledDocument(4, Duration.ofMillis(250)))
        installed.hashCode().assert().isEqualTo(InstalledDocument(4, Duration.ofMillis(250)).hashCode())
        installed.assert().isNotEqualTo(InstalledDocument(5, Duration.ofMillis(250)))
        installed.assert().isNotEqualTo(InstalledDocument(4, Duration.ZERO))
        (installed as Any).assert().isNotEqualTo("InstalledDocument(4, PT0.25S)")
        installed.toString().assert().isEqualTo("InstalledDocument(4, PT0.25S)")
    }

    @Test
    fun `an instance status is its instance, time, documents and format level`() {
        val documents =
            mapOf<SnapshotKey, InstalledDocument>(SnapshotKey.Platform to InstalledDocument(9, Duration.ZERO))
        val status = InstanceStatus("i1", at, documents)
        status.formatLevel.assert().isEqualTo(InstanceStatus.FORMAT_LEVEL)
        status.assert().isEqualTo(InstanceStatus("i1", at, documents))
        status.hashCode().assert().isEqualTo(InstanceStatus("i1", at, documents).hashCode())
        status.assert().isNotEqualTo(InstanceStatus("i2", at, documents))
        status.assert().isNotEqualTo(InstanceStatus("i1", at, documents, formatLevel = 2))
        (status as Any).assert().isNotEqualTo(documents)
        status.toString().assert().startsWith("InstanceStatus(i1, 2026-11-01T00:00:00Z, ")
    }

    @Test
    fun `a candidate names its window and the revision of its base`() {
        StoredCandidate("w", 0, document).baseRevision.assert().isEqualTo(0)
        assertThrownBy<IllegalArgumentException> { StoredCandidate("", 4, document) }
        assertThrownBy<IllegalArgumentException> { StoredCandidate("w", -1, document) }
    }
}
