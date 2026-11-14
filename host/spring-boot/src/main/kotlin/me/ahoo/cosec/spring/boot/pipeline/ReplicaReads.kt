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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.spring.boot.pipeline

import io.lettuce.core.ReadFrom
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory

/**
 * RC1-47 (b): the connection factories of CoSec's Redis templates read from the primary. A [LettuceConnectionFactory]
 * whose client configuration reads from replicas (`spring.data.redis.lettuce.read-from`, or a
 * `LettuceClientConfigurationBuilderCustomizer`, other than the upstream) fails startup. Any other factory's reads are
 * not known here: `storage.md` requires primary reads of it.
 */
internal object ReplicaReads {
    const val LETTUCE_CONNECTION_FACTORY = "org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory"

    /** The name of the bean [instance] is in [beans], for the message. */
    fun nameOf(beans: ListableBeanFactory, instance: Any): String =
        beans.getBeanNamesForType(instance.javaClass).firstOrNull { beans.getBean(it) === instance } ?: "?"

    /**
     * The templates of [type] CoSec may be injected with, by bean name, with their connection factories: the only or
     * primary one when there is one, else every one, since an injection point then resolves by parameter name.
     */
    fun <T : Any> injected(
        beans: ListableBeanFactory,
        provider: ObjectProvider<T>,
        type: Class<T>,
        factoryOf: (T) -> Any?,
    ): List<Pair<String, Any?>> {
        provider.ifUnique?.let { return listOf(nameOf(beans, it) to factoryOf(it)) }
        return beans.getBeansOfType(type).map { (name, template) -> name to factoryOf(template) }
    }

    /** Each of [templates], by bean name, with its connection factory: those that read from replicas fail startup. */
    fun check(templates: List<Pair<String, Any?>>) {
        val replicas = templates.mapNotNull { (name, factory) ->
            val readFrom = (factory as? LettuceConnectionFactory)?.clientConfiguration?.readFrom?.orElse(null)
            // ReadFrom.UPSTREAM and ReadFrom.MASTER are two instances of one class.
            readFrom?.takeIf { it.javaClass != ReadFrom.UPSTREAM.javaClass }?.let { "[$name] (${it.javaClass.simpleName})" }
        }
        check(replicas.isEmpty()) {
            "CoSec's Redis template ${replicas.joinToString()} reads from replicas: a replica lags the primary, and " +
                "CoSec reads Redis as its store of record. Read from the upstream: remove " +
                "spring.data.redis.lettuce.read-from, or the client customizer that sets ReadFrom on its factory; a " +
                "template of your own may read from replicas when it is not one CoSec may be injected with (with " +
                "several templates of a type, mark the one CoSec uses @Primary)."
        }
    }
}
