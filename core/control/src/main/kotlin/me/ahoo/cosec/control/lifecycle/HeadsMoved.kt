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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.control.audit.ChangeEvent
import me.ahoo.cosec.control.audit.MovedHead
import me.ahoo.cosec.control.change.Escalation

/** What [MovedHeads.detect] found once a change was published: the event to record (RC1-33). */
internal class HeadsMoved(
    val kind: ChangeEvent.Kind,
    val moved: List<MovedHead>,
    val escalations: List<Escalation>,
    val note: String,
)
