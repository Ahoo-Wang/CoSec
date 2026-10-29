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

package me.ahoo.cosec.runtime

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * The default request ids of the [Pipeline], for requests without a `CoSec-Request-Id` header: random version 4 UUIDs
 * drawn from [ThreadLocalRandom]. Deliberately not a `StringIdGenerator`, so it cannot end up generating ids that must
 * be unpredictable, such as token ids.
 *
 * Not `UUID.randomUUID()`: its `SecureRandom` refills its buffer from `/dev/urandom` now and then, a blocking file read
 * on whatever thread asks, an event loop included (RFC 0006 §7.1; the end-to-end slice's BlockHound test caught it),
 * and it costs more per request. A request id correlates logs and audit events; it is not a secret.
 */
internal object RandomRequestIds {
    private const val VERSION_MASK = 0xF000L.inv()
    private const val VERSION_4 = 0x4000L
    private const val VARIANT_MASK = 0x3FFFFFFFFFFFFFFFL
    private const val VARIANT_IETF = Long.MIN_VALUE

    fun next(): String {
        val random = ThreadLocalRandom.current()
        val mostSignificant = (random.nextLong() and VERSION_MASK) or VERSION_4
        val leastSignificant = (random.nextLong() and VARIANT_MASK) or VARIANT_IETF
        return UUID(mostSignificant, leastSignificant).toString()
    }
}

/** Which client request ids the pipeline accepts. */
internal object RequestIds {
    /** The longest id accepted; a UUID is 36 characters. */
    const val MAX_LENGTH = 64

    /**
     * Whether [id] is a plain id: 1 to [MAX_LENGTH] characters of `A-Z a-z 0-9 . _ -`. Anything else, with spaces,
     * quotes, control characters or non-ASCII, is replaced.
     */
    fun isAcceptable(id: String): Boolean {
        if (id.isEmpty() || id.length > MAX_LENGTH) {
            return false
        }
        for (c in id) {
            val plain = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '-'
            if (!plain) {
                return false
            }
        }
        return true
    }
}
