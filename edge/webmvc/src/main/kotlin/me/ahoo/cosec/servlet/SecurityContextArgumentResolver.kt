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
package me.ahoo.cosec.servlet

import jakarta.servlet.http.HttpServletRequest
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.servlet.ServletRequests.getSecurityContext
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * A handler method's `SecurityContext` parameter (round 2 M4): the context the adapter bound to the request it
 * permitted, read from the request, never from a thread. The way to read it in a handler, `suspend` and async ones
 * included, which may resume on a thread serving another request. The starter registers it.
 *
 * A nullable parameter is null where CoSec did not handle the request; a non-null one then fails the call.
 */
class SecurityContextArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        SecurityContext::class.java.isAssignableFrom(parameter.nestedParameterType)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): SecurityContext? {
        val context = webRequest.getNativeRequest(HttpServletRequest::class.java)?.getSecurityContext()
        check(context != null || parameter.isOptional) {
            "No SecurityContext for parameter [${parameter.parameterName}] of ${parameter.method}: CoSec did not " +
                "permit this request (it is outside the filter's dispatch, or the filter is not registered)."
        }
        return context
    }
}
