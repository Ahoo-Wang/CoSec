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

/**
 * The audit trail the gateways write to Kafka, as an operator reads it: the records of the topic, from the start, through
 * the broker's own console consumer. A record is `key<TAB>value`, the key being the tenant and the principal.
 */
object Audit {
    /** Every record of the audit topic, as a console consumer reads them. */
    fun records(): List<String> = Compose.execAnyway(
        "kafka",
        "/opt/kafka/bin/kafka-console-consumer.sh",
        "--bootstrap-server",
        "kafka:9092",
        "--topic",
        "cosec-audit",
        "--from-beginning",
        "--timeout-ms",
        "4000",
        "--property",
        "print.key=true",
    ).lines()

    /** The record of the request for [path], once delivered; null before. */
    fun recordOf(path: String): String? = records().firstOrNull { it.contains(path) }

    /**
     * The revision of the app document [record]'s decision read (`snapshotVersion.app`), or null when it read none. The
     * request that made the record went to one gateway, so this is the revision that gateway decided with.
     */
    fun appRevision(record: String): Long? = APP_REVISION.find(record)?.groupValues?.get(1)?.toLong()

    private val APP_REVISION = Regex(""""snapshotVersion":\{[^}]*"app":(\d+)""")
}
