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

package me.ahoo.cosec.spi.id

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RandomIdGeneratorTest {
    @Test
    fun idsAreDistinctVersion4Uuids() {
        val ids = (1..10_000).map { RandomIdGenerator.generate() }
        ids.toSet().assert().hasSize(ids.size)
        ids.forEach {
            val uuid = UUID.fromString(it)
            uuid.version().assert().isEqualTo(4)
            uuid.variant().assert().isEqualTo(2)
            uuid.toString().assert().isEqualTo(it)
        }
    }

    @Test
    fun threadsMakeDistinctIds() {
        val ids = ConcurrentHashMap.newKeySet<String>()
        val threads = 8
        val perThread = 20_000
        val executor = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            executor.execute { repeat(perThread) { ids += RandomIdGenerator.generate() } }
        }
        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.MINUTES).assert().isTrue()
        ids.assert().hasSize(threads * perThread)
    }
}
