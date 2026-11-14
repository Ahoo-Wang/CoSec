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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** What enforcement decides an action and a resource as, for checks and the control plane. */
class EnforceableTest {
    /** PIP-15, CTL-3: the one reading of a resource that checks and the control plane share with enforcement. */
    @Test
    fun enforceableReadsAResourceAsEnforcementDoes() {
        val path = Resource("http", "/orders;x/1", mapOf("k" to "v"))
        val normalized = Enforceable.resource(path)!!
        normalized.type.assert().isEqualTo("http")
        normalized.id.assert().isEqualTo("/orders/1")
        normalized.attributes.assert().isEqualTo(mapOf("k" to "v"))
        val plain = Resource("http", "/orders/1", emptyMap())
        Enforceable.resource(plain).assert().isSameAs(plain)
        for (refused in listOf("/public/../admin", "//admin", "/admin%2F1", "/admin\\1", "/" + "a".repeat(257))) {
            Enforceable.resource(Resource("http", refused, emptyMap())).assert().describedAs(refused).isNull()
        }
        // A typed id is not a path: held to the bounds only, read as written.
        val typed = Resource("order", "a//b/../c", emptyMap())
        Enforceable.resource(typed).assert().isSameAs(typed)
        Enforceable.resource(Resource("order", "a".repeat(257), emptyMap())).assert().isNull()
    }

    /** PIP-18: an http action is read as a request's method; any other as written. */
    @Test
    fun enforceableReadsAnActionAsEnforcementDoes() {
        Enforceable.action(Action("http", "HEAD"))!!.verb.assert().isEqualTo("GET")
        Enforceable.action(Action("http", "delete")).assert().isNull()
        val post = Action("http", "POST")
        Enforceable.action(post).assert().isSameAs(post)
        val typed = Action("order", "read")
        Enforceable.action(typed).assert().isSameAs(typed)
    }
}
