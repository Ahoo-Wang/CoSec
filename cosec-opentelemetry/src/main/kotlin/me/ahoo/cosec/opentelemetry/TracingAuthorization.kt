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

package me.ahoo.cosec.opentelemetry

import io.opentelemetry.context.Context
import me.ahoo.cosec.Delegated
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import reactor.core.publisher.Mono

class TracingAuthorization(override val delegate: Authorization) :
    Authorization,
    Delegated<Authorization> {
    override fun authorize(request: Request, context: SecurityContext): Mono<AuthorizeResult> {
        val parentContext = Context.current()
        val source = delegate.authorize(request = request, context = context)
        return CoSecMonoTrace(parentContext = parentContext, securityContext = context, source = source)
    }
}
