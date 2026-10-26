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

package me.ahoo.cosec.cache.snapshot

import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.authorization.RoleScope
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.cache.AppSpacedRoleId
import me.ahoo.cosec.parity.AuthorizationFactory
import me.ahoo.cosec.parity.FailureView
import me.ahoo.cosec.parity.REPLAY_OK
import me.ahoo.cosec.parity.ReplayExport
import me.ahoo.cosec.parity.ReplaySide
import me.ahoo.cosec.parity.SnapshotAuthorizationFixture
import me.ahoo.cosec.parity.replayMain
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotOptions
import reactor.core.publisher.Mono
import java.io.PrintStream
import java.time.Duration
import kotlin.system.exitProcess

/**
 * The candidate of the RFC 0003 gate (§8.2): the snapshot read path, fed the export the way a deployment's data would
 * reach it. The exported JSON is stored in 7.0's Redis layout, [CoSec7xReader] converts it, [RedisSnapshotPublisher]
 * publishes the documents, and [SnapshotAuthorizationFixture] reads them through [RedisSnapshotDocumentStore] and the
 * snapshot compiler.
 *
 * Everything stays in this JVM: the store is CoCache's in-memory backend, so exported data never reaches a Redis or
 * anything else. An export the reader refuses (RFC 0003 §4.7: grants of an app without an app permission, say) is not
 * replayable, and fails the replay as invalid input with the reader's problems.
 *
 * @param afterPublish tests only: changes the published documents before they are read, to inject a difference
 */
class SnapshotReplaySide(
    private val afterPublish: (RedisSnapshotPublisher) -> Unit = {},
) : ReplaySide {
    override fun replaying(
        export: ReplayExport,
        blacklistChecker: BlacklistChecker,
        rootPrincipalMatcher: RootPrincipalMatcher
    ): DecisionAuthorization {
        val backend = InMemoryBackend()
        val node = SnapshotNode(backend, KEY_PREFIX)
        var fixture: SnapshotAuthorizationFixture? = null
        var replaying = false
        try {
            val conversion = convert(backend, export)
            require(conversion.accepted) {
                "The export cannot be converted to snapshot documents: ${conversion.problems.joinToString("; ")}"
            }
            conversion.contents.forEach { node.publisher.replace(it).block() }
            afterPublish(node.publisher)
            val snapshot = SnapshotAuthorizationFixture(
                node.store,
                options = SnapshotOptions(revalidateInterval = Duration.ZERO),
                blacklistChecker = blacklistChecker,
                rootPrincipalMatcher = rootPrincipalMatcher,
            ).also { fixture = it }
            // Compiled now, while the replay's time conditions are registered; a cold load would compile them too.
            snapshot.load()
            export.apps.filter { SnapshotKey.App.isValidAppId(it.appId) }.forEach { snapshot.load(it.appId) }
            return ReplayedSnapshot(snapshot, node).also { replaying = true }
        } finally {
            if (!replaying) {
                fixture?.close()
                node.close()
            }
        }
    }

    /** The export stored as 7.x data, then read back by [CoSec7xReader]. */
    private fun convert(backend: InMemoryBackend, export: ReplayExport): CoSec7xReader.Conversion {
        val grants = HashSet<AppSpacedRoleId>()
        Raw7xWriter(backend, KEY_PREFIX).use { writer ->
            export.policies.forEach { writer.policy(it.id, CoSecJsonSerializer.writeValueAsString(it.json)) }
            val global = export.policies.filter { it.global }.map { it.id }
            if (global.isNotEmpty()) {
                writer.index(*global.toTypedArray())
            }
            export.apps.forEach { app ->
                app.appPermission?.let { writer.appPermission(app.appId, CoSecJsonSerializer.writeValueAsString(it)) }
                app.grants.forEach { (key, permissionIds) ->
                    val role = checkNotNull(RoleScope.parse(key))
                    val grantId = AppSpacedRoleId(app.appId, role.roleId, role.spaceId)
                    writer.grants(grantId, CoSecJsonSerializer.writeValueAsString(permissionIds))
                    grants += grantId
                }
            }
        }
        val keys = CoSec7xReader.Keys(
            export.policies.mapTo(HashSet()) { it.id },
            export.apps.filter { it.appPermission != null }.mapTo(HashSet()) { it.appId },
            grants,
        )
        return CoSec7xReader(backend, KEY_PREFIX).use { it.read(keys) }
    }

    private class ReplayedSnapshot(
        private val fixture: SnapshotAuthorizationFixture,
        private val node: SnapshotNode,
    ) : DecisionAuthorization, AutoCloseable {
        override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> =
            fixture.decisionAuthorization.decide(request, context)

        override fun close() {
            fixture.close()
            node.close()
        }
    }

    companion object {
        private const val KEY_PREFIX = "cosec-replay"
    }
}

/**
 * `./gradlew :cosec-cocache:snapshotReplay -PreplayDir=<dir>` (RFC 0003 §8.2): the RFC 0002 replay of an export, with
 * the engine over repositories holding it as the baseline (phase 2's implementation) and [SnapshotReplaySide] as the
 * candidate. Failures are compared by their innermost cause (§8.1, X4). Takes the same arguments, writes the same
 * report and exits with the same codes as `engineReplay` (see [replayMain]), under the same gate.
 */
object SnapshotReplay {
    fun run(args: Array<String>, out: PrintStream = System.out, candidate: ReplaySide = SnapshotReplaySide()): Int =
        replayMain(args, out, AuthorizationFactory.ENGINE, candidate, FailureView.INNERMOST_CAUSE)

    @JvmStatic
    fun main(args: Array<String>) {
        val code = run(args)
        if (code != REPLAY_OK) {
            exitProcess(code)
        }
    }
}
