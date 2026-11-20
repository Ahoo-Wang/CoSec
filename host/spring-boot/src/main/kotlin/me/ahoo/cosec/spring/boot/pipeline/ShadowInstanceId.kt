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

package me.ahoo.cosec.spring.boot.pipeline

import java.security.SecureRandom
import java.util.HexFormat
import java.util.UUID

/**
 * This process's id in shadow reports and statuses: unique per process start. Each instance replaces its own
 * cumulative report of a window, so a restarted process that took its predecessor's id would replace the reports that
 * process wrote: its samples lost, and a `CANDIDATE_ERROR` it recorded, which blocks the window, erased. A configured
 * id is therefore a prefix: `<configured>.<suffix>`, the suffix new at every start, so that an ACL can still confine an
 * instance to its keys with `<configured>.*`. Without one, the id is a random UUID, as before.
 */
internal object ShadowInstanceId {
    private const val SEPARATOR = '.'
    private const val SUFFIX_BYTES = 6
    private val random = SecureRandom()

    fun forThisStart(configured: String): String {
        if (configured.isEmpty()) {
            return UUID.randomUUID().toString()
        }
        val suffix = ByteArray(SUFFIX_BYTES).also(random::nextBytes)
        return configured + SEPARATOR + HexFormat.of().formatHex(suffix)
    }
}
