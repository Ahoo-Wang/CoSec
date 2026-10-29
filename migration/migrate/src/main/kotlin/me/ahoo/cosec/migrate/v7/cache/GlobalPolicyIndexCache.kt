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

package me.ahoo.cosec.migrate.v7.cache

import me.ahoo.cocache.api.SetCache

/**
 * Ids of the global policies, a single set under [CACHE_KEY]. Element updates are atomic, so concurrent writers on
 * any instance cannot lose entries. See [V7CacheSpecs.globalPolicyIndex] for the declaration.
 *
 * @author ahoo wang
 */
internal interface GlobalPolicyIndexCache : SetCache<String, String> {
    companion object {
        const val CACHE_KEY = ""
    }
}
