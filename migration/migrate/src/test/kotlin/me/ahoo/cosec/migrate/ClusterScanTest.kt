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

package me.ahoo.cosec.migrate

import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.sync.RedisCommands
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/** R2C-30: a scan on a node of a Redis Cluster would see one shard's keys; it stops before it reads anything. */
class ClusterScanTest {
    @Test
    fun `a node of a Redis Cluster is refused before any key is scanned`() {
        val commands = mockk<RedisCommands<String, String>>()
        every { commands.info("cluster") } returns "# Cluster\r\ncluster_enabled:1\r\n"
        assertThrownBy<IllegalStateException> { RedisAccess.scan(commands, "cosec:*") }
            .hasMessageContaining("Redis Cluster")
        verify(exactly = 0) { commands.scan(any<ScanCursor>(), any<ScanArgs>()) }
    }

    @Test
    fun `a standalone Redis is scanned`() {
        val commands = mockk<RedisCommands<String, String>>(relaxed = true)
        every { commands.info("cluster") } returns "# Cluster\r\ncluster_enabled:0\r\n"
        RedisAccess.isCluster(commands).assert().isFalse()
    }
}
