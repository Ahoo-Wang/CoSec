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

package me.ahoo.cosec.parity

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.context.request.AppId
import me.ahoo.cosec.authorization.SnapshotEngineAuthorization
import me.ahoo.cosec.authorization.engine.CompiledApp
import me.ahoo.cosec.authorization.engine.CompiledPolicies
import me.ahoo.cosec.authorization.engine.PolicySnapshotCompiler
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.snapshot.SnapshotOptions
import me.ahoo.cosec.snapshot.SnapshotSource
import tools.jackson.databind.ObjectMapper

/**
 * The snapshot read path over any [SnapshotDocumentStore], assembled as RFC 0003 PR-5 will: a [SnapshotSource] with
 * the policy compiler, and the engine authorization reading it. For tests and benchmarks of other modules, which
 * cannot see these internal classes; it goes away when PR-5 wires them.
 */
class SnapshotAuthorizationFixture(
    store: SnapshotDocumentStore,
    mapper: ObjectMapper = CoSecJsonSerializer,
    options: SnapshotOptions = SnapshotOptions(),
) : AutoCloseable {
    private val compiler = PolicySnapshotCompiler(mapper)
    private val source: SnapshotSource<CompiledPolicies, CompiledApp> =
        SnapshotSource(store, compiler.policies, compiler.app, options)

    val authorization: Authorization = SnapshotEngineAuthorization(source)

    /** Loads the policies document and, when given, [appId]'s; returns the revisions loaded. */
    fun load(appId: AppId? = null): Pair<Long, Long> {
        val snapshot = source.load(appId).get()
        return snapshot.version.shared to snapshot.version.scoped
    }

    /** Whether the documents [appId] needs are loaded, so an evaluation would not touch the store. */
    fun isLoaded(appId: AppId? = null): Boolean = source.peek(appId) != null

    override fun close() {
        source.close()
    }
}
