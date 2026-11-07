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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.scope.Scope

/**
 * Which documents a request reads (RFC 0004 §7.4): the one rule that enforcement ([SuspendingSnapshotSource]),
 * `simulate`, `explain` and the shadow comparison all apply, so the control plane decides on the documents enforcement
 * reads (CTL-3, RC1-17). Always the platform's and, unless blocked, the tenant's; the app's by [appOf]. Never more than
 * enforcement reads: an app document brings its rate limit rules, so reading it where enforcement does not would change
 * the decision.
 */
@InternalCoSecApi
object ViewDocuments {
    /**
     * The app whose document a request in [scope] reads, or null: the scope's app, when it has one and either roles
     * there, or a tenant document that binds one of that app's permission policies to `Anyone` or to a principal
     * ([tenantNeedsApp], from `CompiledDocument.needsApp` or `PolicyDocumentCompiler.boundApps`, which computes it).
     * Inline: the request path allocates nothing for it.
     */
    inline fun appOf(scope: Scope, tenantNeedsApp: (app: String) -> Boolean): AppId? {
        val app = scope.app
        if (app.value.isEmpty()) {
            return null
        }
        return if (scope.roles.isNotEmpty() || tenantNeedsApp(app.value)) app else null
    }
}
