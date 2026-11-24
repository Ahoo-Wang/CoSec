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
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.spring.boot.control.ControlPlaneCondition
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import org.springframework.util.ClassUtils

/**
 * What a service in `cosec.pipeline.mode=gateway` trusts, and the two ways its host can break that trust at startup.
 *
 * - `STA-7`, `STA-9`: the host believes any `CoSec-Identity` header, which carries no signature. The boundary is the
 *   network, so the operator states that only the gateway can reach it ([PipelineProperties.ONLY_GATEWAY_CAN_REACH_KEY]),
 *   or the gateway and the services they name ([PipelineProperties.ONLY_TRUSTED_CALLERS_CAN_REACH_KEY]); without one,
 *   or with anything but `true`, startup fails and says what to write. The guards follow the gate: they hold for a host
 *   in `gateway` mode and for one that decides with policies and takes its caller from the identity.
 * - `STA-6`: the gateway decides the method of the request line. A hidden-method filter in the service would run
 *   another one (`_method` in a form body, which the gateway cannot strip), so a DENY on `http:DELETE` would be stepped
 *   around; startup fails when one is on. In-process, CoSec's filter runs after that filter (`PIP-19`): nothing changes
 *   there.
 */
internal object GatewayModeTrust {
    private const val MVC_PROPERTY = "spring.mvc.hiddenmethod.filter.enabled"
    private const val WEBFLUX_PROPERTY = "spring.webflux.hiddenmethod.filter.enabled"
    private const val SERVLET_REGISTRATION = "org.springframework.boot.web.servlet.FilterRegistrationBean"
    private val FILTERS = listOf(
        "org.springframework.web.filter.HiddenHttpMethodFilter",
        "org.springframework.web.filter.reactive.HiddenHttpMethodFilter",
    )

    /**
     * `STA-7`, `STA-9`: fails unless [Environment] states what can reach this host: `true` exactly (any case, no spaces),
     * for [PipelineProperties.ONLY_GATEWAY_CAN_REACH_KEY] (only the gateway can) or
     * [PipelineProperties.ONLY_TRUSTED_CALLERS_CAN_REACH_KEY] (the gateway and the services the operator names can).
     * Answers the key that was stated, the gateway's first. [host] says what makes this host believe the header, for the
     * message: the property that wired the gate, or the application's own gate bean ([describe] by default).
     * [controlPlane] says whether this host also runs the control plane, where a claimed principal can publish and approve
     * changes ([controlPlaneWired] by default); the message names that only there.
     */
    fun requireBoundaryStated(
        environment: Environment,
        host: String = describe(environment),
        controlPlane: Boolean = controlPlaneWired(environment),
    ): String {
        val gateway = written(environment, PipelineProperties.ONLY_GATEWAY_CAN_REACH_KEY)
        val trusted = written(environment, PipelineProperties.ONLY_TRUSTED_CALLERS_CAN_REACH_KEY)
        val stated = listOf(
            PipelineProperties.ONLY_GATEWAY_CAN_REACH_KEY to gateway,
            PipelineProperties.ONLY_TRUSTED_CALLERS_CAN_REACH_KEY to trusted,
        ).firstOrNull { (_, value) -> value != null && value.equals("true", ignoreCase = true) }?.first
        check(stated != null) {
            "$host: this host believes any CoSec-Identity header it receives, and the header carries no signature, so " +
                "whoever can reach this host is whoever they claim to be" + exposure(environment, controlPlane) +
                " The network is the boundary. That is safe only if nothing but the CoSec gateway " +
                "(or, for a host that services call directly, the gateway and the services you name) can reach it: a " +
                "Kubernetes NetworkPolicy that admits those pods only, mutual TLS, or a private network. State it once " +
                "it is true: ${PipelineProperties.ONLY_GATEWAY_CAN_REACH_KEY}=true (the environment variable " +
                "COSEC_PIPELINE_IDENTITY_ONLYGATEWAYCANREACH=true) when only the gateway can reach it, or " +
                "${PipelineProperties.ONLY_TRUSTED_CALLERS_CAN_REACH_KEY}=true (the environment variable " +
                "COSEC_PIPELINE_IDENTITY_ONLYTRUSTEDCALLERSCANREACH=true) when services reach it too. " +
                (if (gateway == null) "It is not set" else "It is [$gateway]") +
                (if (trusted == null) "" else ", and ${PipelineProperties.ONLY_TRUSTED_CALLERS_CAN_REACH_KEY} is [$trusted]") +
                " (STA-7, STA-9, https://cosec.ahoo.me/operations/deployment#services-behind-the-gateway)."
        }
        return stated
    }

    /**
     * What a caller that claims any principal reaches on this host: a service in `gateway` mode holds no documents and
     * no control plane, so only its own routes; another host, its routes as that principal; and where the control plane
     * runs, every tenant's policies, with their approval.
     */
    private fun exposure(environment: Environment, controlPlane: Boolean): String = when {
        controlPlane ->
            ", the platform administrator included, and can publish any policy of any tenant: the control plane " +
                "decides each change by the policies, for the principal the header names. It still refuses an invalid " +
                "change and records every change in the audit under the principal claimed, but neither defends against " +
                "a compromised workload that can reach this host, which can also approve its own changes by claiming " +
                "a second principal."
        isGatewayMode(environment) ->
            " to this service's routes, any user of any tenant: the service serves each request as the principal and " +
                "tenant the header names, and nothing here decides it again."
        else -> " to this host's routes, the platform administrator included, and is served as the principal the header names."
    }

    /**
     * Whether this host runs the control plane: never in `gateway` mode, which wires no documents for it to write; else
     * the control plane's own condition ([ControlPlaneCondition.wired]).
     */
    fun controlPlaneWired(
        environment: Environment,
        classLoader: ClassLoader? = GatewayModeTrust::class.java.classLoader,
    ): Boolean = !isGatewayMode(environment) && ControlPlaneCondition.wired(classLoader, environment)

    /** Whether [PipelineProperties.MODE_KEY] is `gateway`: a service behind a gateway, which believes the identity it is stated. */
    fun isGatewayMode(environment: Environment): Boolean =
        environment.getProperty(PipelineProperties.MODE_KEY).equals("gateway", ignoreCase = true)

    /**
     * [key] as Spring's binder reads it, which is how the starter's own property check ([me.ahoo.cosec.spring.boot.UnknownProperties])
     * reads an environment variable: `COSEC_PIPELINE_IDENTITY_ONLYGATEWAYCANREACH`, the words joined, since each `_` is a
     * `.`. `Environment.getProperty` would answer to `ONLY_GATEWAY_CAN_REACH` instead, a spelling the property check refuses.
     */
    private fun written(environment: Environment, key: String): String? =
        Binder.get(environment).bind(key, String::class.java).orElse(null)

    /** Whether [key] is stated `true` (any letter case), read as [requireBoundaryStated] reads it. */
    fun isStated(environment: Environment, key: String): Boolean = written(
        environment,
        key
    ).equals("true", ignoreCase = true)

    /** Whether the properties wire the identity gate ([OnIdentityGate]), for the check that follows a gate wired another way. */
    fun wiredByProperty(environment: Environment): Boolean =
        isGatewayMode(environment) ||
            environment.getProperty(PipelineProperties.CALLER_KEY).equals("forwarded-identity", ignoreCase = true)

    /** What makes the host believe the identity, for a message: its mode, or the caller it was told to take. */
    fun describe(environment: Environment): String =
        if (isGatewayMode(environment)) {
            "${PipelineProperties.MODE_KEY}=gateway"
        } else {
            "${PipelineProperties.CALLER_KEY}=forwarded-identity"
        }

    /** The host whose web gate is an `IdentityGate` that no property wired: the application's own bean, named. */
    fun describeOwnGate(beans: ListableBeanFactory): String {
        val names = beans.getBeanNamesForType(IdentityGate::class.java, true, false)
        return "the web gate of this host is an IdentityGate bean of the application, [${names.joinToString()}]"
    }

    /** Who the statement [key] says can reach the host. */
    fun admitted(key: String): String =
        if (key == PipelineProperties.ONLY_TRUSTED_CALLERS_CAN_REACH_KEY) {
            "the CoSec gateway and the services the operator named"
        } else {
            "the CoSec gateway, which strips the clients' own,"
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
        if (bean != null) {
            return "a HiddenHttpMethodFilter bean, [$bean]"
        }
        // A servlet filter may be registered without being a bean of its own: `FilterRegistrationBean(HiddenHttpMethodFilter())`.
        val registration = ClassUtils.isPresent(SERVLET_REGISTRATION, null) && ClassUtils.isPresent(FILTERS.first(), null)
        return registration.takeIf { it }?.let { ServletRegistrations.hiddenMethod(beans) }
            ?.let { "a FilterRegistrationBean of a HiddenHttpMethodFilter, [$it]" }
    }

    fun requireNoHiddenMethod(environment: Environment, beans: ListableBeanFactory, host: String? = null) {
        val found = hiddenMethod(environment, beans) ?: return
        error(
            "${host ?: describe(environment)}: $found. The gateway decided the method of the request line, but this service " +
                "would run a POST as the method a form field `_method` names, and the gateway cannot remove a field " +
                "from a form body: a DENY on http:DELETE, or an ALLOW for http:POST only, is stepped around. Remove " +
                "$MVC_PROPERTY and $WEBFLUX_PROPERTY (off by default) and any HiddenHttpMethodFilter of the " +
                "application (STA-6). In-process hosts (`enforce`) are not affected: CoSec's filter runs after it there."
        )
    }
}

/** The servlet side of [GatewayModeTrust], apart so that a reactive host never loads the servlet classes. */
private object ServletRegistrations {
    /** The name of a `FilterRegistrationBean` whose filter is a hidden-method filter, or null. */
    fun hiddenMethod(beans: ListableBeanFactory): String? =
        beans.getBeansOfType(org.springframework.boot.web.servlet.FilterRegistrationBean::class.java, true, false)
            .entries.firstOrNull { (_, registration) -> registration.filter is org.springframework.web.filter.HiddenHttpMethodFilter }
            ?.key
}
