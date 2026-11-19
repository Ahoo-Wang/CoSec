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

package me.ahoo.cosec.webmvc

/**
 * Why a reader of this stack that must find a security context found none (UA-7), in one place so the readers cannot
 * drift: what is missing, why, what to do, and the stack's other readers.
 */
internal object Unbound {
    /** [ServletRequests.requireSecurityContext], and a handler's non-null `SecurityContext` parameter. */
    const val ON_REQUEST: String =
        "No SecurityContext on this request: CoSec's filter did not handle it (it is not registered, or this code runs " +
            "outside its dispatch). Register CoSec's filter (the starter does). In a handler, take SecurityContext " +
            "as a handler parameter; on the request thread of blocking code, SecurityContextHolder." +
            "requireSecurityContext() reads the same context."

    /** [SecurityContextHolder.requireSecurityContext]. */
    const val ON_THREAD: String =
        "No SecurityContext is bound to this thread: it is bound only on the request thread of blocking code. In a " +
            "handler, take SecurityContext as a handler parameter; elsewhere read it from the request " +
            "(ServletRequests.requireSecurityContext(request)). In a suspend handler the holder works only with " +
            "context propagation (io.micrometer:context-propagation and spring.reactor.context-propagation=auto); an " +
            "async dispatch and a thread the application started never have it."
}
