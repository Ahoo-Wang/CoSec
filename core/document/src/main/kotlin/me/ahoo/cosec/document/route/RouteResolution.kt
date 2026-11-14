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

package me.ahoo.cosec.document.route

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource

/** What a route map says about one request. */
sealed interface RouteResolution {
    /** No route matches: the request stays in the `http` namespace (RFC 0005 §5.3, `ACT-2`). */
    data object Unmapped : RouteResolution

    class Mapped internal constructor(val route: Route, val action: Action, val resource: Resource) : RouteResolution

    /**
     * A route matches, but a value it captured cannot be part of a typed resource id: it holds `/` (a decoded
     * `%2F`), which would change the id's segments. The request is refused, never evaluated as another resource.
     */
    class Invalid internal constructor(val reason: String) : RouteResolution
}
