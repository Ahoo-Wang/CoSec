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

package me.ahoo.cosec.compat.side

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.control.CoCacheControlStore
import me.ahoo.cosec.cocache.snapshot.createPolicySnapshotCache
import me.ahoo.cosec.control.store.ControlStore

/**
 * A document's revision index and the app list (MIX-15) through this version's control store over Redis, under
 * `prefix`: `control.index.read` and `control.apps.read` describe what the store read; `control.index.rewrite` and
 * `control.apps.rewrite` read and write back conditionally, as a publish does.
 */
internal object ControlStoreOperations {
    val ALL: Map<String, (Map<String, String>) -> String> = mapOf(
        "control.index.read" to { a -> store(a) { it.index(DocumentOperations.key(a.getValue("key")))?.value?.toString() ?: "none" } },
        "control.index.rewrite" to { a ->
            store(a) { store ->
                val key = DocumentOperations.key(a.getValue("key"))
                store.index(key)?.let { store.putIndex(key, it.digest, it.value).toString() } ?: "none"
            }
        },
        "control.apps.read" to { a -> store(a) { it.apps()?.value?.toString() ?: "none" } },
        "control.apps.rewrite" to { a ->
            store(a) { store -> store.apps()?.let { store.putApps(it.digest, it.value).toString() } ?: "none" }
        },
    )

    private fun store(arguments: Map<String, String>, block: suspend (ControlStore) -> String): String =
        Redis.caches { factory ->
            val prefix = arguments.getValue("prefix")
            val heads = factory.createPolicySnapshotCache(CoSecCacheSpecs.policySnapshot(prefix))
            runBlocking { block(CoCacheControlStore.create(factory, heads, prefix)) }
        }
}
