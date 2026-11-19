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

package me.ahoo.cosec.spring.boot.pipeline

import me.ahoo.cosec.annotations.InternalCoSecApi
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.core.env.Environment
import org.springframework.util.ClassUtils

/**
 * What a service in `cosec.pipeline.mode=gateway` trusts, and the two ways its host can break that trust at startup.
 *
 * - `STA-7`: the mode believes any `CoSec-Identity` header, which carries no signature. The boundary is the network, so
 *   the operator states that only the gateway can reach the service ([PipelineProperties.ONLY_GATEWAY_CAN_REACH_KEY]);
 *   without it, or with anything but `true`, startup fails and says what to write.
 * - `STA-6`: the gateway decides the method of the request line. A hidden-method filter in the service would run
 *   another one (`_method` in a form body, which the gateway cannot strip), so a DENY on `http:DELETE` would be stepped
 *   around; startup fails when one is on. In-process, CoSec's filter runs after that filter (`PIP-19`): nothing changes
 *   there.
 */
internal object GatewayModeTrust {
    private const val MVC_PROPERTY = "spring.mvc.hiddenmethod.filter.enabled"
    private const val WEBFLUX_PROPERTY = "spring.webflux.hiddenmethod.filter.enabled"
    private val FILTERS = listOf(
        "org.springframework.web.filter.HiddenHttpMethodFilter",
        "org.springframework.web.filter.reactive.HiddenHttpMethodFilter",
    )

    /** `STA-7`: fails unless [Environment] states the boundary, `true` exactly (any case, no spaces). */
    fun requireBoundaryStated(environment: Environment) {
        val written = environment.getProperty(PipelineProperties.ONLY_GATEWAY_CAN_REACH_KEY)
        check(written != null && written.equals("true", ignoreCase = true)) {
            "cosec.pipeline.mode=gateway: this service believes any CoSec-Identity header it receives, and the header " +
                "carries no signature, so whoever can reach this service is whoever they claim to be, the platform " +
                "administrator included. That is safe only if nothing but the CoSec gateway can reach it: a Kubernetes " +
                "NetworkPolicy that admits the gateway's pods only, mutual TLS between the gateway and this service, or " +
                "a private network that only the gateway is on. State it once it is true: " +
                "${PipelineProperties.ONLY_GATEWAY_CAN_REACH_KEY}=true (the environment variable " +
                "COSEC_PIPELINE_IDENTITY_ONLY_GATEWAY_CAN_REACH=true). " +
                (if (written == null) "It is not set" else "It is [$written]") +
                " (STA-7, https://cosec.ahoo.me/operations/deployment#services-behind-the-gateway)."
        }
    }

    /** `STA-6`: what makes this host run a request as another method than the gateway decided, or null. */
    fun hiddenMethod(environment: Environment, beans: ListableBeanFactory): String? {
        val property = listOf(MVC_PROPERTY, WEBFLUX_PROPERTY).firstOrNull {
            environment.getProperty(it).equals("true", ignoreCase = true)
        }
        if (property != null) {
            return "$property=true"
        }
        val bean = FILTERS.filter { ClassUtils.isPresent(it, null) }.firstNotNullOfOrNull { type ->
            beans.getBeanNamesForType(ClassUtils.resolveClassName(type, null), true, false).firstOrNull()
        }
        return bean?.let { "a HiddenHttpMethodFilter bean, [$it]" }
    }

    fun requireNoHiddenMethod(environment: Environment, beans: ListableBeanFactory) {
        val found = hiddenMethod(environment, beans) ?: return
        error(
            "cosec.pipeline.mode=gateway: $found. The gateway decided the method of the request line, but this service " +
                "would run a POST as the method a form field `_method` names, and the gateway cannot remove a field " +
                "from a form body: a DENY on http:DELETE, or an ALLOW for http:POST only, is stepped around. Remove " +
                "$MVC_PROPERTY and $WEBFLUX_PROPERTY (off by default) and any HiddenHttpMethodFilter of the " +
                "application (STA-6). In-process hosts (`enforce`) are not affected: CoSec's filter runs after it there."
        )
    }
}
