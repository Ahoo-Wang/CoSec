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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.route.RouteMap
import me.ahoo.cosec.model.condition.AttributeCatalog

/** What a candidate is compiled and analyzed against, besides the platform document. */
@InternalCoSecApi
class CandidateCompile(
    /** The host's `AttributeProvider` catalog: a candidate compiles against it as an instance would. */
    val attributes: AttributeCatalog = AttributeCatalog.NONE,
    /** The headers the host reads credentials from besides the fixed ones, which no reference may read (`CND-5`). */
    val credentialHeaders: Set<String> = emptySet(),
    /**
     * The services' route maps (`cosec.control.analysis.route-maps`), for the `ROUTES` finding (UC-1): which `http:`
     * DENY statements and rate limit rules of a candidate a mapped route escapes. Null when none were given: the
     * finding then says so rather than list nothing.
     */
    val routes: RouteMap? = null,
)
