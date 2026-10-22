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

package me.ahoo.cosec.authorization.engine

import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.parity.LegacyData
import me.ahoo.cosec.parity.PUBLISHED_AT
import me.ahoo.cosec.snapshot.DocumentState
import me.ahoo.cosec.snapshot.EntryOrigin
import me.ahoo.cosec.snapshot.Snapshot
import me.ahoo.cosec.snapshot.SnapshotEntry
import me.ahoo.cosec.snapshot.SnapshotKey

/*
 * The data model, the JSON builders and the P5 generator live in the test fixtures (me.ahoo.cosec.parity), shared
 * with cosec-cocache; what needs cosec-core's internal types stays here.
 */

/** The snapshot a SnapshotSource would install for [data], compiled directly: no threads. */
internal fun compiledSnapshot(data: LegacyData, appId: AppId?, compiler: PolicySnapshotCompiler): PolicySnapshot {
    val policies = data.policiesDocument()?.let { document ->
        compileState { compiler.policies.compile(SnapshotKey.Policies, document) }
    } ?: DocumentState.Absent
    val app = appId?.let { id ->
        val key = SnapshotKey.App(id)
        val state = data.appDocument(id)?.let { document -> compileState { compiler.app.compile(key, document) } }
            ?: DocumentState.Absent
        snapshotEntry(key, state)
    }
    return Snapshot(snapshotEntry(SnapshotKey.Policies, policies), app)
}

@Suppress("TooGenericExceptionCaught")
private fun <T : Any> compileState(compile: () -> T): DocumentState<T> = try {
    DocumentState.Present(compile())
} catch (error: Exception) {
    DocumentState.Failed(error)
}

internal fun <T : Any> snapshotEntry(key: SnapshotKey, state: DocumentState<T>): SnapshotEntry<T> {
    val revision = if (state is DocumentState.Present) 1L else 0L
    val origin = if (state is DocumentState.Present) EntryOrigin.DOCUMENT else EntryOrigin.ABSENT
    return SnapshotEntry(key, state, revision, null, PUBLISHED_AT, origin)
}
