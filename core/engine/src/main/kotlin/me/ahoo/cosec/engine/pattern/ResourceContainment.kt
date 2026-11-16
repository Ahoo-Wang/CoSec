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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.engine.pattern

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.condition.Containment

/**
 * What [PatternContainment.contains] proved about two resource patterns.
 */
@InternalCoSecApi
sealed interface ResourceContainment {
    val answer: Containment

    /**
     * Every resource the inner pattern matches, the outer one matches. [variables] maps a variable of the outer pattern
     * to the variable of the inner one that captures the same value on every such resource; a variable of the outer
     * pattern that no variable of the inner one corresponds to is absent.
     */
    @InternalCoSecApi
    class Contained(val variables: Map<String, String>) : ResourceContainment {
        override val answer: Containment get() = Containment.YES
    }

    /** Not proven. */
    @InternalCoSecApi
    class Unknown(val reason: String) : ResourceContainment {
        override val answer: Containment get() = Containment.UNKNOWN
    }
}
