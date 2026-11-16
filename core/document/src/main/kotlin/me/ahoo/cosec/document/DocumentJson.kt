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

package me.ahoo.cosec.document

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * What every section of a document's JSON shares: the root path, the node factory, and the one value, an [Actor], that
 * more than one section holds. It depends on nothing, so a section reads it while any other object is initialising.
 */
internal object DocumentJson {
    /** `*`: any app, any space, anyone, or every permission. */
    internal const val WILDCARD: String = Match.WILDCARD

    /** The path of the root of a document, as errors name it. */
    internal const val ROOT = "$"

    internal val factory = JsonNodeFactory.instance

    internal fun actor(node: DocumentNode): Actor {
        node.requireObject("tenant", "principal")
        return Actor(TenantId(node.required("tenant").nonEmpty()), PrincipalId(node.required("principal").nonEmpty()))
    }

    internal fun DocumentNode.nonEmpty(): String = text().ifEmpty { fail("must not be empty") }

    internal fun actor(actor: Actor): ObjectNode =
        factory.objectNode().put("tenant", actor.tenant.value).put("principal", actor.principal.value)
}
