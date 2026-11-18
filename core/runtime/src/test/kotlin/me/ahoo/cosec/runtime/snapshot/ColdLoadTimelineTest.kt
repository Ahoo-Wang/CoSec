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

package me.ahoo.cosec.runtime.snapshot

import org.junit.jupiter.api.Test

/** Measurement only, not for merge: prints [ColdLoadTimeline] for `Slot` and the runner. */
class ColdLoadTimelineTest {
    @Test
    fun timeline() {
        // DEBUG lines on every install and eviction would be most of what is timed.
        val logger = org.slf4j.LoggerFactory.getLogger("me.ahoo.cosec") as ch.qos.logback.classic.Logger
        val level = logger.level
        logger.level = ch.qos.logback.classic.Level.WARN
        try {
            System.err.println(ColdLoadTimeline.both())
        } finally {
            logger.level = level
        }
    }
}
