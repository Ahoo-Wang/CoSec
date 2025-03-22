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

package me.ahoo.cosec.policy.action

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object ActionMatcherFactoryProvider {
    private val log = KotlinLogging.logger {}
    private val actionMatcherFactories: ConcurrentHashMap<String, ActionMatcherFactory> = ConcurrentHashMap()

    init {
        ServiceLoader.load(ActionMatcherFactory::class.java)
            .forEach {
                log.info {
                    "Load $it to register."
                }
                register(it)
            }
    }

    fun register(actionMatcherFactory: ActionMatcherFactory) {
        log.info {
            "Register $actionMatcherFactory."
        }
        actionMatcherFactories[actionMatcherFactory.type] = actionMatcherFactory
    }

    fun get(type: String): ActionMatcherFactory? {
        return actionMatcherFactories[type]
    }

    fun getRequired(type: String): ActionMatcherFactory {
        return requireNotNull(get(type)) {
            "ActionMatcherFactory[$type] not found."
        }
    }
}
