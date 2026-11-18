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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * What a compiler compiles a document against besides the document itself, for the line that says a document does not
 * compile: an app or tenant document compiles against the platform's limits and attributes, so the same revision can
 * compile one day and not the next. CoSec's own: [PlatformCompiler] says it; another compiler says nothing.
 */
@InternalCoSecApi
fun interface CompileInputs {
    /** The inputs [key] compiles against now, as a phrase ("the limits and attributes of platform revision 12"); null for none. */
    fun against(key: SnapshotKey): String?
}
