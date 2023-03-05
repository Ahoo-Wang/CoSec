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

package me.ahoo.cosec.policy.condition

import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object ConditionMatcherFactoryProvider {
    private val log = LoggerFactory.getLogger(ConditionMatcherFactoryProvider::class.java)
    private val actionMatcherFactories: ConcurrentHashMap<String, ConditionMatcherFactory> = ConcurrentHashMap()

    init {
        ServiceLoader.load(ConditionMatcherFactory::class.java)
            .forEach {
                if (log.isInfoEnabled) {
                    log.info("Load $it to register.")
                }
                register(it)
            }
    }

    fun register(conditionMatcherFactory: ConditionMatcherFactory) {
        if (log.isInfoEnabled) {
            log.info("Register $conditionMatcherFactory.")
        }
        actionMatcherFactories[conditionMatcherFactory.type] = conditionMatcherFactory
    }

    fun get(type: String): ConditionMatcherFactory? {
        return actionMatcherFactories[type]
    }

    fun getRequired(type: String): ConditionMatcherFactory {
        return requireNotNull(get(type)) {
            "ConditionMatcherFactory[$type] not found."
        }
    }
}
