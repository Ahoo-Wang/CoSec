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

package me.ahoo.cosec.spring.boot.starter.authorization.cache

import me.ahoo.cocache.api.ReadableCache
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.core.Spec
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cache.CoSecCacheSpecs

/**
 * Creates CoSec's cache [spec] through CoCache's [CoCacheFactory], as [type].
 *
 * Like the caches an application declares, a CoSec cache takes the `cocache.defaults.*`, `cocache.overrides.*` and
 * `cocache.caches.[name].*` properties and the application's `CacheSpecCustomizer`s, field by field. What CoSec's
 * correctness depends on is locked ([CoSecCacheSpecs.locks]): the storage layout stays CoSec's
 * (`cosec.key-prefix`, not `cocache.key-prefix`), a policy or permission store never expires and
 * never reads a failed Redis read as a miss, and the memberships always expire. A property that sets a locked field
 * fails startup; a customizer that changes one throws.
 *
 * CoSec does not declare its caches as beans CoCache creates, so CoCache does not create them a second time.
 */
internal fun <C : ReadableCache<*, *>> CoCacheFactory.createCoSecCache(type: Class<C>, spec: Spec<*, *>): C =
    create(type, spec, CoSecCacheSpecs.locks(spec.name))

/** Referenced by name: CoCache's starter is on the classpath only with `cosec-cocache`. */
internal const val COCACHE_AUTO_CONFIGURATION = "me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration"
