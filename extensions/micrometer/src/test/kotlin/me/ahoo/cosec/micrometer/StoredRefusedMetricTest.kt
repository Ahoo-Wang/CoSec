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

package me.ahoo.cosec.micrometer

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.StoredRecord
import me.ahoo.cosec.document.json.StoredRefusals
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * OBS-12: `cosec.stored.refused` reads what [StoredRefusals] counts (the counting is `document`'s `StoredCritTest`), one
 * series per kind of record and reason, and no other.
 */
class StoredRefusedMetricTest {
    @Test
    fun `each series reads the count of its record and reason, and the series are the closed sets only`() {
        val registry = SimpleMeterRegistry()
        CoSecMeters.bind(registry, StoredRefusals)
        for (record in StoredRecord.entries) {
            for (reason in StoredRefusals.Reason.entries) {
                registry.get(CoSecMeters.STORED_REFUSED).tag(CoSecMeters.RECORD_TAG, record.tag)
                    .tag(CoSecMeters.REASON_TAG, reason.tag).functionCounter().count()
                    .assert().isEqualTo(StoredRefusals.count(record, reason).toDouble())
            }
        }
        registry.find(CoSecMeters.STORED_REFUSED).functionCounters().size.assert()
            .isEqualTo(StoredRecord.entries.size * StoredRefusals.Reason.entries.size)
    }
}
