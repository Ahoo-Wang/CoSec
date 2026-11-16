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
import me.ahoo.cosec.control.change.CheckedApps
import me.ahoo.cosec.control.store.AppList
import me.ahoo.cosec.control.store.Head
import me.ahoo.cosec.control.store.Versioned
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * [context], keeping the digest of every head it served first, and of the app list: what one check of a candidate read
 * (RC1-33).
 */
internal class RecordingContext(private val context: ChangeChecks.Context) : ChangeChecks.Context by context {
    val read = LinkedHashMap<SnapshotKey, SnapshotDigest?>()

    /** The app list as first read; `null` until it is. */
    var apps: CheckedApps? = null
        private set

    override suspend fun head(key: SnapshotKey): Head? = context.head(key).also { read.putIfAbsent(key, it?.digest) }

    override suspend fun apps(): Versioned<AppList>? = context.apps().also {
        if (apps == null) apps = CheckedApps(it?.digest)
    }
}
