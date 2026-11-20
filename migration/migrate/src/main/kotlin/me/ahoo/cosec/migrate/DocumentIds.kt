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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.engine.CompileError
import me.ahoo.cosec.document.DocumentIds as Ids

/**
 * The ids of a converted document the compiler refuses (`DOC-13`: a control character or an unpaired surrogate; RC1-52,
 * `IDN-10`), told apart from its other errors: the conversion reports them as INVALID_ID, at their place in the
 * document. 7.x JSON can write either as an escape (`"\uD800"`, `"\n"`); a Redis key would write the surrogate as `?`,
 * naming another id's keys, and a log line would print the line break. The compiler's own walk decides which ids those
 * are, so the conversion and every instance agree. The document keys are held to the app and tenant id rule where the
 * converter places them.
 */
internal object DocumentIds {
    /** [errors] of compiling [document]: those that are its refused ids, and the others. */
    fun split(document: PolicyDocument, errors: List<CompileError>): Pair<List<CompileError>, List<CompileError>> {
        val ids = Ids.unprintable(document).toSet()
        return errors.partition { it in ids }
    }
}
