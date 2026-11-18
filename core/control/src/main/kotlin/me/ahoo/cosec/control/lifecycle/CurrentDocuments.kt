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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The documents as they are now: what the checks and the grant analysis combine a candidate with. Current heads, the
 * back-references of the platform's and apps' policies, and the app list ([me.ahoo.cosec.control.store.ControlStore.apps],
 * RC1-4).
 */
@InternalCoSecApi
interface CurrentDocuments {
    suspend fun head(key: SnapshotKey): Head?

    suspend fun referrers(target: SnapshotKey, policy: PolicyId): Set<SnapshotKey>

    suspend fun apps(): Versioned<AppList>?
}
