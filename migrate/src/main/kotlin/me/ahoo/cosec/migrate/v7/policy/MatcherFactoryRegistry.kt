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

package me.ahoo.cosec.migrate.v7.policy

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of matcher factories keyed by type, seeded from [ServiceLoader] and extended via [register].
 *
 * Shared implementation behind [me.ahoo.cosec.policy.action.ActionMatcherFactoryProvider] and
 * [me.ahoo.cosec.policy.condition.ConditionMatcherFactoryProvider].
 */
internal class MatcherFactoryRegistry<F : Any>(
    factoryClass: Class<F>,
    private val typeOf: (F) -> String
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val factoryName = factoryClass.simpleName
    private val factories = ConcurrentHashMap<String, F>()

    init {
        ServiceLoader.load(factoryClass).forEach { register(it) }
    }

    fun register(factory: F) {
        log.info { "Register $factoryName[${typeOf(factory)}] - $factory." }
        factories[typeOf(factory)] = factory
    }

    fun get(type: String): F? = factories[type]

    fun getRequired(type: String): F = requireNotNull(get(type)) {
        "$factoryName[$type] not found."
    }
}
