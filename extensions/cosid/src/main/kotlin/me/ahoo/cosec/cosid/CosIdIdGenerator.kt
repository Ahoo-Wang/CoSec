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

package me.ahoo.cosec.cosid

import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosid.StringIdGenerator
import me.ahoo.cosid.runtime.CosIdRuntime
import me.ahoo.cosid.runtime.RuntimeSpec

/**
 * CoSec's ids from a CosId 5 generator ([IdGenerator]): request, decision event, token (`jti`) and control-plane
 * change ids, each the generator's next id as its codec writes it.
 *
 * An id goes into headers, URLs and Redis keys, so it must be plain: 1 to 64 characters of `A-Z a-z 0-9 . _ -`. A
 * sample is checked here, so that a codec writing anything else (a `:` prefix, say) fails startup rather than every
 * request. Pick a generator that never waits for its store, such as a Snowflake one: [generate] runs on the request's
 * thread, an event loop included. CoSec never uses these ids as secrets.
 */
class CosIdIdGenerator(private val ids: StringIdGenerator) : IdGenerator {
    init {
        val sample = ids.generate()
        require(PLAIN.matches(sample)) {
            "A CosId generator for CoSec must make ids of 1 to 64 characters of A-Z a-z 0-9 . _ -, not [$sample]."
        }
    }

    override fun generate(): String = ids.generate()

    companion object {
        private val PLAIN = Regex("[A-Za-z0-9._-]{1,64}")

        /** The generator [name] of [runtime], with its codec: CosId's `default` unless named. */
        @JvmStatic
        @JvmOverloads
        fun of(runtime: CosIdRuntime, name: String = RuntimeSpec.DEFAULT_GENERATOR): CosIdIdGenerator =
            CosIdIdGenerator(runtime.stringGenerator(name))
    }
}
