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

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * The default [IdGenerator]: random version 4 UUIDs drawn from [ThreadLocalRandom], 36 characters each.
 *
 * Not unpredictable, and not meant to be: [ThreadLocalRandom] is not a secure source. That is what keeps it cheap and
 * non-blocking. `UUID.randomUUID()` draws from a `SecureRandom`, which refills its buffer from `/dev/urandom` now and
 * then, a blocking file read on whatever thread asks, an event loop included (RFC 0006 §7.1; the end-to-end slice's
 * BlockHound test caught it), and it costs more per request. Never use these ids as secrets.
 */
object RandomIdGenerator : IdGenerator {
    private const val VERSION_MASK = 0xF000L.inv()
    private const val VERSION_4 = 0x4000L
    private const val VARIANT_MASK = 0x3FFFFFFFFFFFFFFFL
    private const val VARIANT_IETF = Long.MIN_VALUE

    override fun generate(): String {
        val random = ThreadLocalRandom.current()
        val mostSignificant = (random.nextLong() and VERSION_MASK) or VERSION_4
        val leastSignificant = (random.nextLong() and VARIANT_MASK) or VARIANT_IETF
        return UUID(mostSignificant, leastSignificant).toString()
    }
}
