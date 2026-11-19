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

package me.ahoo.cosec.document.route

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * A statement a mapped route escapes (UC-1): [statement] (`DENY <policy>#<index>` or `rate limit <id>`) would apply to
 * [route]'s requests as `http:<METHOD>` on their path, and does not apply to them as the route's own action and
 * resource. [location] is the route map the route came from.
 */
@InternalCoSecApi
data class RouteEscape(val statement: String, val route: Route, val location: String) {
    override fun toString(): String = "$statement escapes ${route.method} ${route.path} (${route.action} on ${route.resource}, $location)"
}
