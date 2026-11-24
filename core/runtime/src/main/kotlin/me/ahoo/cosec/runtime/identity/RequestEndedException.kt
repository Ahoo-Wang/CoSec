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
package me.ahoo.cosec.runtime.identity

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * An identity asked of a request that has ended (`PIP-26`, `CTL-77`, DR1-F-2): [IdentityIssuer] states only a request
 * still in progress, as a check of an ended request is refused in process. Whoever relays a caller refuses the call it
 * was for, before anything is sent.
 */
@InternalCoSecApi
class RequestEndedException(val requestId: String) :
    IllegalStateException("Request [$requestId] has ended: its caller is not stated (PIP-26).")
