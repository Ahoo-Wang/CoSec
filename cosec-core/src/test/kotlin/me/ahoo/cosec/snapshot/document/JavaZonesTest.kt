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

package me.ahoo.cosec.snapshot.document

import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class JavaZonesTest {
    @Test
    fun `region zones follow java time, daylight saving included`() {
        val berlin = JavaZones.resolve("Europe/Berlin")!!
        for (instant in listOf("2026-01-15T12:00:00Z", "2026-07-15T12:00:00Z", "2090-07-15T12:00:00Z")) {
            val epoch = Instant.parse(instant).epochSecond
            berlin.offsetSecondsAt(epoch).assert().describedAs(instant)
                .isEqualTo(ZoneId.of("Europe/Berlin").rules.getOffset(Instant.parse(instant)).totalSeconds)
        }
        JavaZones.resolve("Asia/Shanghai")!!.offsetSecondsAt(0).assert().isEqualTo(8 * 3600)
        JavaZones.resolve("Europe/Berlin").assert().isSameAs(berlin)
        JavaZones.resolve("Nowhere/City").assert().isNull()
        JavaZones.RESOLVER.resolve("+08:00")!!.offsetSecondsAt(0).assert().isEqualTo(8 * 3600)
        JavaZones.RESOLVER.resolve("UTC")!!.offsetSecondsAt(0).assert().isEqualTo(0)
    }

    @Test
    fun `a tenant key names exactly one document`() {
        SnapshotKey.Tenant("(platform)").toString().assert().isEqualTo("tenant:(platform)")
        SnapshotKey.Tenant("acme").kind.assert().isEqualTo(me.ahoo.cosec.snapshot.SnapshotKind.TENANT)
        SnapshotKey.Platform.toString().assert().isEqualTo("platform")
        for (id in listOf("", "*", "a:b", "a\u0007b", " ")) {
            assertThrownBy<IllegalArgumentException> { SnapshotKey.Tenant(id) }
        }
    }
}
