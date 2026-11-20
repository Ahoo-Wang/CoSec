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

package me.ahoo.cosec.system

import me.ahoo.test.asserts.assert
import java.net.URI

/** A process's Prometheus scrape endpoint, read as an operator's dashboard reads it. */
object Metrics {
    /**
     * The sum of the series of [name] (as the scrape endpoint spells it, `cosec_snapshot_loads_total`) that carry every one
     * of [tags], each as `key="value"`; 0 when the process has not counted any yet.
     */
    fun sum(management: URI, name: String, vararg tags: String): Long {
        val scrape = Http.get(management.resolve("/actuator/prometheus"))
        scrape.status.assert().describedAs("the scrape endpoint of $management: ${scrape.status}").isEqualTo(200)
        return scrape.body.lineSequence()
            .filter { it.startsWith("$name{") && tags.all { tag -> it.contains(tag) } }
            .sumOf { it.substringAfterLast(' ').toDouble().toLong() }
    }
}
