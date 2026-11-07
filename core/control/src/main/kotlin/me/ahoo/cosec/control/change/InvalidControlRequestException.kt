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

package me.ahoo.cosec.control.change

/**
 * The control plane refused a request it was given, for the reason [message] says (RC1-39): the control API answers it
 * `400` with that message as its `detail`. Only the control plane's own checks of a caller's request throw it, so the
 * message says what the caller sent and never what is stored. Any other [IllegalArgumentException] is a `400` without
 * detail; a stored document or record that does not decode is a `500`.
 *
 * An [IllegalArgumentException], as these checks threw before it existed. Its cause, a parse error of what the caller
 * sent, is never shown.
 */
class InvalidControlRequestException(
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

/** [require], for a check of a caller's request: throws [InvalidControlRequestException] with [message]. */
internal inline fun requireRequest(value: Boolean, message: () -> String) {
    if (!value) {
        throw InvalidControlRequestException(message())
    }
}
