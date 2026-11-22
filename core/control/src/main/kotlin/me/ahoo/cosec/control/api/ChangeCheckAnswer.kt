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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.document.json.CoSecJson
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/**
 * What a check answers (`CTL-78`, `contract/control/change-check.json`): of the change the proposal would make, what its
 * checks found, as [ChangeAnswer] writes those members, so a check and the proposal it stands for answer them alike. A
 * check makes no change, so nothing that names one is written: no id, author, note or time, and no `candidate`. The
 * candidate names the change that confirms a lapsing DENY (`onMissingConfirmed.change`), so a check's would be another
 * document than the proposal's.
 */
@InternalCoSecApi
object ChangeCheckAnswer {
    /** The members of a change answer a check writes. */
    val MEMBERS: List<String> = listOf("state", "base", "findings", "escalations")

    fun tree(change: ChangeRequest): ObjectNode {
        val answer = ChangeAnswer.tree(change)
        val node = JsonNodeFactory.instance.objectNode()
        MEMBERS.forEach { member -> answer.get(member)?.let { node.set(member, it) } }
        return node
    }

    fun response(change: ChangeRequest): ControlHttpResponse =
        ControlHttpResponse(ControlHttpSupport.OK, ControlHttp.JSON, CoSecJson.mapper.writeValueAsBytes(tree(change)))
}
