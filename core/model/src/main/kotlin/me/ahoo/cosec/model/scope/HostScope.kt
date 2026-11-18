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

package me.ahoo.cosec.model.scope

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Identifiers

/**
 * The app the host serves (RFC 0004 §4.6): on a gateway, the route's metadata `cosec-app-id`, else
 * `cosec.authorization.scope.app-id`; on any other host, that property (RC1-6). Null when neither names one: then the
 * credential's app applies (RFC 0012 §3.5).
 */
class HostScope(val app: AppId?) {
    init {
        require(app == null || ScopeResolver.isValidAppId(app.value)) {
            "Host app id [${Identifiers.printable(app?.value.orEmpty())}] is not valid."
        }
    }
}
