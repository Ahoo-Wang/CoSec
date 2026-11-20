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
import me.ahoo.cosec.engine.CompileError
import me.ahoo.cosec.model.CoSecException
import me.ahoo.cosec.model.Identifiers

/**
 * A document that decodes but does not compile; it is never installed, and every request needing it fails closed. Its
 * message quotes what the document wrote, and a host logs it when it cannot install a stored document: it is
 * [Identifiers.printable], so a stored document that predates `DOC-13` forges no line (RC1-15). [errors] keep the text as
 * written, for a caller that escapes it itself (the control plane's JSON body).
 */
class DocumentCompileException @InternalCoSecApi constructor(
    val document: String,
    @InternalCoSecApi val errors: List<CompileError>,
    cause: Throwable? = null,
) :
    CoSecException(
        Identifiers.printable(
            "The $document document does not compile: " + errors.joinToString("; ") { "${it.path}: ${it.message}" },
        ),
        cause,
    )
