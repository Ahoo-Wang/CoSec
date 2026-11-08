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

package me.ahoo.cosec.spi.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Identifiers

/**
 * Document keys as stores and codecs write them (`platform`, `app:<id>`, `tenant:<id>`), for what reads them back: one
 * way to put a stored key or id in a message.
 */
@InternalCoSecApi
object SnapshotKeyText {
    /**
     * [text] for a message or a log line: a control character as its `\uXXXX` escape, so none can forge a line (RC1-15),
     * and an unpaired surrogate too, which a UTF-8 log would write as `?`, naming another id (RC1-52).
     */
    fun printable(text: String): String = Identifiers.printable(text)
}
