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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.change.Severity
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.RolePermissions
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/** What a tenant's roles may mean other than what they seem to (RFC 0012 §7.5). */
internal object RoleChecks {
    private const val UNPROCESSABLE = 422

    /**
     * What a tenant's roles may mean other than what they seem to (RFC 0012 §7.5): one named as a system role of its app
     * grants the union of both, and a permission of a role in one space that is restricted to the default space never
     * applies there. Neither is a grant nobody analyzed, so both only warn.
     */
    suspend fun roleWarnings(
        candidate: PolicyDocument,
        context: ChangeChecks.Context,
        checkedWith: MutableMap<SnapshotKey, SnapshotDigest?>,
        findings: MutableList<Finding>,
    ) {
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
                findings += roleWarning("$.roles[$r].id", "[${role.id.value}] is also a system role of app $app: a member holding it gets both")
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

    private fun roleWarning(path: String, message: String) =
        Finding("REFERENCES", path, message, UNPROCESSABLE, Severity.WARNING)
}
