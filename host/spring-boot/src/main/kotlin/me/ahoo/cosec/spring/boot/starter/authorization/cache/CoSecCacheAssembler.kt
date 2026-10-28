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

package me.ahoo.cosec.spring.boot.starter.authorization.cache

import me.ahoo.cocache.api.Cache
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CacheSpec
import me.ahoo.cocache.core.SetCacheSpec
import me.ahoo.cocache.core.proxy.CacheProxies
import me.ahoo.cocache.spring.boot.CacheSpecCustomizer
import me.ahoo.cocache.spring.boot.CoCacheProperties
import me.ahoo.cosec.cache.CoSecCacheSpecs
import org.springframework.beans.factory.ObjectProvider

/**
 * Creates CoSec's caches on the application's [CacheRuntime].
 *
 * Like caches declared through CoCache, a CoSec cache takes the application's [CacheSpecCustomizer]s first and the
 * `cocache.defaults.*` / `cocache.caches.[name].*` policy properties last. Two things differ:
 *
 * - [CoSecCacheSpecs.pinnedPolicy] is applied after both, so neither can make a policy or permission cache expire
 *   or degrade a failed Redis read into a miss, nor make any CoSec cache reclaim (delete) a value it cannot read,
 *   such as an unmigrated 6.x revocation;
 * - the storage layout stays CoSec's (`cosec.authorization.cache.key-prefix`): the `key-prefix` property, namespace
 *   and key encoder of a customizer are ignored.
 *
 * CoSec does not declare its caches as `CacheSpec` beans, so CoCache does not create them a second time.
 */
class CoSecCacheAssembler(
    private val runtime: CacheRuntime,
    private val customizers: List<CacheSpecCustomizer>,
    private val properties: CoCacheProperties?,
) {
    fun <C : Cache<K, V>, K : Any, V : Any> create(type: Class<C>, spec: CacheSpec<K, V>): C =
        CacheProxies.create(type, runtime.create(assemble(spec)))

    fun <K : Any, V : Any> assemble(spec: CacheSpec<K, V>): CacheSpec<K, V> {
        val builder = spec.toBuilder()
        customizers.filter { it.supports(spec.name) }.forEach { it.customize(builder) }
        val policy = CoSecCacheSpecs.pinnedPolicy(
            spec.name,
            properties?.policyFor(spec.name, builder.policy) ?: builder.policy
        )
        return builder.namespace(spec.namespace)
            .keyPrefix(spec.keyPrefix)
            .keyEncoder(spec.keyEncoder)
            .policy(policy)
            .build()
    }

    fun <K : Any, E : Any> assemble(spec: SetCacheSpec<K, E>): SetCacheSpec<K, E> {
        val builder = spec.toBuilder()
        customizers.filter { it.supports(spec.name) }.forEach { it.customize(builder) }
        val policy = CoSecCacheSpecs.pinnedPolicy(
            spec.name,
            properties?.setPolicyFor(spec.name, builder.policy) ?: builder.policy
        )
        return builder.namespace(spec.namespace)
            .keyPrefix(spec.keyPrefix)
            .keyEncoder(spec.keyEncoder)
            .policy(policy)
            .build()
    }
}

internal fun cosecCacheAssembler(
    runtime: CacheRuntime,
    customizers: ObjectProvider<CacheSpecCustomizer>,
    properties: ObjectProvider<CoCacheProperties>,
): CoSecCacheAssembler = CoSecCacheAssembler(runtime, customizers.orderedStream().toList(), properties.ifAvailable)

/** Referenced by name: CoCache's starter is on the classpath only with `cosec-cocache`. */
internal const val COCACHE_AUTO_CONFIGURATION = "me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration"
