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

package me.ahoo.cosec.engine

import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.Scope
import me.ahoo.cosec.model.Subject

/** The matchers each evaluation called, in order: `guard:<group id>` and `rule:<rule name>`. */
class CallLog {
    val calls = mutableListOf<String>()

    fun clear() = calls.clear()
}

enum class GuardBehavior { MATCH, MISS, THROW }

enum class RuleBehavior { MATCH, ACTION_MISS, CONDITION_MISS, THROW }

/** Thrown by test matchers; one instance per matcher, so decisions compare equal across evaluations. */
class MatcherException(message: String) : RuntimeException(message)

fun request(blocked: Boolean = false, root: Boolean = false): AuthzRequest =
    AuthzRequest(
        subject = Subject(
            id = "user",
            tenantId = "tenant",
            authenticated = true,
            roles = setOf("admin"),
            policies = setOf("p"),
            attributes = emptyMap(),
            root = root,
        ),
        scope = Scope.declared("tenant", "app", "default"),
        action = Action("http", "GET"),
        resource = Resource("http", "/orders/1", emptyMap()),
        environment = Environment(remoteIp = "127.0.0.1", requestId = "req", deviceId = "dev", blocked = blocked),
    )
