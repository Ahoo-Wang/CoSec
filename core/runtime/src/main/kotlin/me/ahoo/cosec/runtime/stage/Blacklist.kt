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

package me.ahoo.cosec.runtime.stage

import me.ahoo.cosec.model.decision.Cause

/**
 * What the blacklist attributes say of a request (RFC 0006 §5.4.4): blocked when any of them says so; else
 * [unavailable] when any of them could not be read, which is never "not blocked".
 */
internal class Blacklist(val blocked: Boolean, val unavailable: Cause?) {
    companion object {
        val BLOCKED = Blacklist(true, null)
        val NOT_BLOCKED = Blacklist(false, null)
    }
}
