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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.lifecycle

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ControlStatus.UNPROCESSABLE
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.change.Severity
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.RolePermissions
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import kotlin.coroutines.cancellation.CancellationException

/** What a tenant's roles may mean other than what they seem to (RFC 0012 §7.5). */
internal object RoleChecks {
    private val log = KotlinLogging.logger {}

    /**
     * What a tenant's roles may mean other than what they seem to (RFC 0012 §7.5): one named as a system role of its app
     * grants the union of both, and a permission of a role in one space that is restricted to the default space never
     * applies there. Neither is a grant nobody analyzed, so both only warn.
     */
    suspend fun roleWarnings(
        candidate: PolicyDocument,
        context: CurrentDocuments,
        checkedWith: MutableMap<SnapshotKey, SnapshotDigest?>,
        findings: MutableList<Finding>,
    ) {
        if (candidate.kind == DocumentKind.APP) {
            sharedSystemRoles(candidate, context, findings)
            return
        }
        if (candidate.kind != DocumentKind.TENANT) {
            return
        }
        for ((r, role) in candidate.roles.withIndex()) {
            val app = role.app?.value?.takeIf(SnapshotKey.App::isValidAppId) ?: continue
            val key = SnapshotKey.App(app)
            val head = context.head(key)
            checkedWith[key] = head?.digest
            val document = head?.document ?: continue
            if (document.roles.any { it.id == role.id }) {
                findings += roleWarning("$.roles[$r].id", "[${role.id.value}], a role of this tenant, is also a system role of app $app: a member holding it gets both")
            }
            val space = role.space?.value?.takeIf { it.isNotEmpty() } ?: continue
            val listed = (role.permissions as? RolePermissions.Listed)?.ids.orEmpty()
            val defaultSpaceOnly = document.catalog?.groups.orEmpty().filterNot { it.spaced }
                .flatMap { it.permissions }.filter { it.actions != null }.mapTo(HashSet()) { it.id }
            listed.withIndex().filter { it.value in defaultSpaceOnly }.forEach { (p, permission) ->
                findings += roleWarning(
                    "$.roles[$r].permissions[$p]",
                    "[$permission] applies only in the default space, so it never applies in [$space]",
                )
            }
        }
    }

    /**
     * A system role of an app candidate whose id another app's head defines too (`ROL-12`): a membership is
     * `role@space`, with no app in it, so a member whose roles are not issued per app (a provider's, or a token bound to
     * no app) and who holds the role once holds it in every app that defines it. That may be meant; it only warns. It reads the app
     * list and the head of every app it names, never a tenant's document. Since it only warns, those heads are not what
     * the change was checked with (an unrelated app's publish does not move it), and one that cannot be read is a warning
     * of its own, never a failed check (DR1-E-11).
     */
    private suspend fun sharedSystemRoles(
        candidate: PolicyDocument,
        current: CurrentDocuments,
        findings: MutableList<Finding>
    ) {
        if (candidate.roles.isEmpty()) return
        val defining = otherAppsRoles(candidate, current.unrecorded, findings) ?: return
        for ((r, role) in candidate.roles.withIndex()) {
            val apps = defining[role.id] ?: continue
            findings += roleWarning(
                "$.roles[$r].id",
                "[${role.id.value}], a system role of this app, is also a system role of app ${apps.joinToString()}: " +
                    "a member who holds it once " +
                    "holds it in ${if (apps.size == 1) "both apps" else "all ${apps.size + 1} apps"} unless their roles " +
                    "are issued per app; prefix the id with the app " +
                    "(${candidate.owner}.${role.id.value}) when that is not meant",
            )
        }
    }

    /**
     * The apps other than [candidate]'s owner that define each system role id, read from [context]; null when the app
     * list cannot be read. What cannot be read is a warning in [findings] (DR1-E-11).
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun otherAppsRoles(
        candidate: PolicyDocument,
        context: CurrentDocuments,
        findings: MutableList<Finding>
    ): Map<RoleId, Set<String>>? {
        val others = try {
            context.apps()?.value?.entries.orEmpty().filter { it != candidate.owner && SnapshotKey.App.isValidAppId(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "The app list could not be read: no system role of ${candidate.owner} is compared." }
            findings += roleWarning("$.roles", "The app list could not be read: whether another app defines these roles is not known")
            return null
        }
        val defining = HashMap<RoleId, MutableSet<String>>()
        val unread = ArrayList<String>()
        for (app in others.sorted()) {
            val head = try {
                context.head(SnapshotKey.App(app))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "The head of app $app could not be read; its system roles are not compared." }
                unread += app
                continue
            }
            head?.document?.roles?.forEach { defining.getOrPut(it.id) { sortedSetOf() } += app }
        }
        if (unread.isNotEmpty()) {
            findings += roleWarning(
                "$.roles",
                "The documents of app ${unread.joinToString()} could not be read: whether they define these roles is not known",
            )
        }
        return defining
    }

    private fun roleWarning(path: String, message: String) =
        Finding("REFERENCES", path, message, UNPROCESSABLE, Severity.WARNING)
}
