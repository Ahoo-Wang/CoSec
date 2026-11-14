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

/**
 * One route of a route map (RFC 0005 §5.3): requests with [method] whose path [path] matches have the action
 * [action] (`service:verb`) on the resource [resource], a typed resource template such as `order/{orderId}` whose
 * variables are captures of [path].
 */
class Route(val method: String, val path: String, val action: String, val resource: String)
