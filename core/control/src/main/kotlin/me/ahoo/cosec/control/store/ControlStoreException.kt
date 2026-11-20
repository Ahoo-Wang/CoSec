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

package me.ahoo.cosec.control.store

import me.ahoo.cosec.annotations.ExperimentalCoSecApi

/**
 * The store could not answer (R2C-6, `CTL-52`): it was not reached, did not answer in time, refused the command, or a
 * conditional write could not be attempted often enough. What it would have answered is not known, so the caller
 * neither reads it as absent nor goes on as if it had written: the control API answers 503, and a maintenance pass
 * does nothing on that pass. A value the store answered with that does not decode is not this: it is a
 * [me.ahoo.cosec.spi.snapshot.SnapshotFormatException], a 500.
 */
@ExperimentalCoSecApi
class ControlStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
