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
package me.ahoo.cosec.context

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.token.AccessToken

/**
 * Abstract Security Context Parser .
 *
 * @author ahoo wang
 */
abstract class AbstractSecurityContextParser<R> :
    SecurityContextParser<R> {
    override fun parse(request: R): SecurityContext {
        val accessToken = getAccessToken(request) ?: return SimpleSecurityContext.ANONYMOUS
        val principal = asPrincipal(accessToken)
        return SimpleSecurityContext(principal)
    }

    protected abstract fun getAccessToken(request: R): AccessToken?

    protected abstract fun asPrincipal(accessToken: AccessToken): CoSecPrincipal
}
