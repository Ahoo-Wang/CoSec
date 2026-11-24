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

package me.ahoo.cosec.spring.boot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.cosec.spring.boot.pipeline.PipelineProperties
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.core.env.Environment
import org.springframework.util.ClassUtils

/**
 * `STA-1` for the adapter itself, not only its module (DR1-G-6): a web host with CoSec on runs one of CoSec's gates, as
 * a bean or, on a servlet host, as the filter of a registration. The module on the classpath is not enough: the
 * application may have excluded the auto-configuration that registers the gate, or replaced its bean with one of
 * another type, and the host would serve every request undecided.
 *
 * Found by type, never by bean name: the adapters' beans are `@ConditionalOnMissingBean`, so an application's own
 * registration of the same filter is a gate too.
 */
internal object WebGateCheck {
    private const val SERVLET_FILTER = "me.ahoo.cosec.webmvc.PipelineServletFilter"
    private val GATES = listOf(
        SERVLET_FILTER,
        "me.ahoo.cosec.webflux.PipelineWebFilter",
        "me.ahoo.cosec.gateway.PipelineGatewayFilter",
    )
    private const val SERVLET_REGISTRATION = "org.springframework.boot.web.servlet.FilterRegistrationBean"
    private const val ENFORCE = "enforce"

    /** The name of a bean that is a CoSec gate, or registers one, or null. */
    fun gate(beans: ListableBeanFactory, classLoader: ClassLoader?): String? {
        val bean = GATES.filter { ClassUtils.isPresent(it, classLoader) }.firstNotNullOfOrNull { type ->
            beans.getBeanNamesForType(ClassUtils.resolveClassName(type, classLoader), true, false).firstOrNull()
        }
        if (bean != null) {
            return bean
        }
        val servlet = ClassUtils.isPresent(SERVLET_REGISTRATION, classLoader) &&
            ClassUtils.isPresent(SERVLET_FILTER, classLoader)
        return if (servlet) ServletGates.registration(beans) else null
    }

    fun require(beans: ListableBeanFactory, classLoader: ClassLoader?, environment: Environment) {
        if (gate(beans, classLoader) != null) {
            return
        }
        val mode = environment.getProperty(PipelineProperties.MODE_KEY)
        // A gateway host decides only in enforce mode: its gate is the gateway filter, which no other mode registers.
        check(!ClassUtils.isPresent(GATEWAY_GLOBAL_FILTER, classLoader) || mode == null || mode.equals(ENFORCE, true)) {
            "CoSec is on (cosec.enabled), but this Spring Cloud Gateway host is in ${PipelineProperties.MODE_KEY}=$mode: " +
                "a gateway host decides only in enforce mode (the default), where its PipelineGatewayFilter decides " +
                "every route, and no other mode registers a gate on it, so every request would pass undecided. " +
                "Remove ${PipelineProperties.MODE_KEY}, or turn CoSec off with cosec.enabled=false (STA-1)."
        }
        error(
            "CoSec is on (cosec.enabled), but this web host registers none of its gates (PipelineServletFilter, " +
                "PipelineWebFilter, PipelineGatewayFilter), as a bean or a filter registration: every request would " +
                "reach the application undecided. Keep CoSecPipelineWebAutoConfiguration (do not exclude it), or " +
                "register the adapter yourself, or turn CoSec off with cosec.enabled=false (STA-1)."
        )
    }
}

/** The servlet side of [WebGateCheck], apart so that a reactive host never loads the servlet classes. */
private object ServletGates {
    fun registration(beans: ListableBeanFactory): String? =
        beans.getBeansOfType(org.springframework.boot.web.servlet.FilterRegistrationBean::class.java, true, false)
            .entries.firstOrNull { (_, registration) -> registration.filter is me.ahoo.cosec.webmvc.PipelineServletFilter }
            ?.key
}
