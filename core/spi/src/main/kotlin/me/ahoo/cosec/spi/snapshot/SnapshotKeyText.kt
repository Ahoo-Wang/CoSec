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

/**
 * Document keys as stores and codecs write them (`platform`, `app:<id>`, `tenant:<id>`), for what reads them back: one
 * test of the app keys earlier builds wrote that no key can name now, and one way to put a stored key or id in a
 * message.
 */
@InternalCoSecApi
object SnapshotKeyText {
    private const val APP = "app:"

    /**
     * Whether [text] is an app key earlier builds wrote (any id without `:`) that the app id rule now refuses (blank,
     * `*`, a control character, too long; SCP-7): no [SnapshotKey] names it, no request is ever in that app, and no
     * such document can be read or published. A reader skips what names one; anything else it cannot read is still
     * malformed.
     */
    fun isUnnamedApp(text: String): Boolean {
        val app = text.removePrefix(APP)
        return text.startsWith(APP) && app.isNotEmpty() && ':' !in app && !SnapshotKey.App.isValidAppId(app)
    }

    /** [text] for a message or a log line: a control character as its `\uXXXX` escape, so none can forge a line. */
    fun printable(text: String): String =
        text.map { if (it.isISOControl()) "\\u%04X".format(it.code) else it.toString() }.joinToString("")
}
