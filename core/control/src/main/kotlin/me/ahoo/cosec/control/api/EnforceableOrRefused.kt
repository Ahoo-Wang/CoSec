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
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.runtime.http.Enforceable
import me.ahoo.cosec.runtime.http.PathNormalizer

/**
 * An action or a resource as enforcement would decide it ([Enforceable]), or the 400 that says it is not one enforcement
 * decides: what a simulated request and an access query are both held to, in the one wording.
 */
internal object EnforceableOrRefused {
    /** [action] as enforcement would decide it (`PIP-18`): [Enforceable]. */
    fun action(action: Action): Action = Enforceable.action(action)
        ?: throw ChangeRejectedException(
            BAD_REQUEST,
            "The action is not one enforcement decides: an http method with a lower-case letter is refused as a " +
                "request's is; http:HEAD is decided as http:GET."
        )

    /** [resource] as enforcement would decide it (RC1-41, review L5 of #1200): [Enforceable]. */
    fun resource(resource: Resource): Resource = Enforceable.resource(resource)
        ?: throw ChangeRejectedException(
            BAD_REQUEST,
            "The resource is not one enforcement decides: an http path is refused as a request's is (a dot segment, " +
                "an empty segment, an encoded separator, a backslash, a malformed escape or its length), and any " +
                "resource is at most ${PathNormalizer.MAX_PATH_LENGTH} characters, " +
                "${PathNormalizer.MAX_SEGMENT_LENGTH} per segment."
        )

    private const val BAD_REQUEST = 400
}
