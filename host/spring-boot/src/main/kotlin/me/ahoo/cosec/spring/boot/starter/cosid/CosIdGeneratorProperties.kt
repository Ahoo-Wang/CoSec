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

package me.ahoo.cosec.spring.boot.starter.cosid

import me.ahoo.cosec.api.CoSec
import me.ahoo.cosid.runtime.RuntimeSpec
import org.springframework.boot.context.properties.ConfigurationProperties

/** `cosec.cosid.*`: which of the application's CosId generators gives CoSec its ids (`cosec-cosid`). */
@ConfigurationProperties(prefix = CosIdGeneratorProperties.PREFIX)
class CosIdGeneratorProperties {
    /** The CosId generator whose ids CoSec takes, with its codec; CosId's `default` unless set. */
    var generator: String = RuntimeSpec.DEFAULT_GENERATOR

    companion object {
        const val PREFIX = CoSec.COSEC_PREFIX + "cosid"
        const val GENERATOR_KEY = "$PREFIX.generator"
    }
}
