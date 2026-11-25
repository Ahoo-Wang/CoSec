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

package me.ahoo.cosec.compat

import io.lettuce.core.RedisClient
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * What each instance keeps for itself, and the one part of it that crosses between versions. Memberships are held by
 * each instance alone, and only their invalidations cross: one version's invalidation drops the other's copy (MIX-10).
 * A route map is each instance's own file, so a fleet that rolls one map out to both versions answers alike (MIX-19).
 * Kafka events go out to consumers outside CoSec only: no module of CoSec reads them back (MIX-20).
 */
class LocalStateCompatTest {
    private val map = """{"service":"wms","routes":[""" +
        """{"method":"GET","path":"/warehouses/{id}","action":"wms:warehouse.read","resource":"warehouse/{id}"},""" +
        """{"method":"DELETE","path":"/admin/warehouses/{id}","action":"wms:warehouse.purge","resource":"warehouse/{id}"}]}"""

    @Test
    fun `an invalidation either version publishes drops the other's copy of a membership`() {
        for ((publisher, holder) in PAIRS) {
            val prefix = prefix()
            holder("memberships.watch", "prefix" to prefix).assert().isEqualTo("ok")
            holder("memberships.held", "prefix" to prefix).assert().isEqualTo("k1,k2")
            publisher("memberships.publish", "prefix" to prefix, "key" to "k1").assert().isEqualTo("ok")
            val deadline = System.nanoTime() + BOUND_NANOS
            while (holder("memberships.held", "prefix" to prefix) != "k2" && System.nanoTime() < deadline) {
                Thread.sleep(POLL_MILLIS)
            }
            holder("memberships.held", "prefix" to prefix).assert().describedAs("$publisher -> $holder").isEqualTo("k2")
            // The memberships themselves were never written to Redis: were they, they would be a format two versions read.
            RedisClient.create("redis://localhost:6379").use { client ->
                client.connect().use { it.sync().keys("$prefix*") }.assert().describedAs("$publisher -> $holder").isEmpty()
            }
        }
    }

    @Test
    fun `one route map is resolved alike by both versions`() {
        val requests = "GET /warehouses/7\nGET /warehouses/7/\nDELETE /admin/warehouses/7\nGET /other\nGET /warehouses/%2e%2e/admin"
        val current = Side.CURRENT("routes.resolve", "map" to map, "requests" to requests)
        current.lines().first().assert().startsWith("mapped ")
        Side.PREVIOUS("routes.resolve", "map" to map, "requests" to requests).assert().isEqualTo(current)
    }

    /** A consumer of Kafka in any main source: what would make an event a format two versions of CoSec exchange. */
    @Test
    fun `no module of CoSec consumes Kafka`() {
        val root = Path.of(checkNotNull(System.getProperty("cosec.compat.root")) { "cosec.compat.root is not set" })
        val consumers = Regex("""KafkaConsumer|@KafkaListener|ConsumerFactory|KafkaReceiver|ReceiverOptions|\.subscribe\(.*[Tt]opic""")
        val found = Files.walk(root).use { paths ->
            paths.filter { it.extension == "kt" && it.toString().contains("/src/main/") && "/build/" !in it.toString() }
                .filter { consumers.containsMatchIn(it.readText()) }
                .map { root.relativize(it).toString() }.toList()
        }
        found.assert().isEmpty()
    }

    private companion object {
        const val BOUND_NANOS = 5_000_000_000L
        const val POLL_MILLIS = 20L
    }
}
