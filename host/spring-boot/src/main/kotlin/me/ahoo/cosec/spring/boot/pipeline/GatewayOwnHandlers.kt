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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.annotation.MergedAnnotations
import org.springframework.stereotype.Controller
import org.springframework.web.reactive.function.server.RouterFunction

/**
 * UB-10: on a Spring Cloud Gateway host CoSec is a global filter, which runs on gateway routes only. The application's
 * own controllers and functional routes are mapped before any route (Spring WebFlux's annotated-controller mapping has
 * order 0 and the router-function mapping -1, the gateway's route mapping 1), so they are answered with no CoSec
 * decision at all. Startup says so, once, naming them.
 */
internal object GatewayOwnHandlers {
    private val log = KotlinLogging.logger {}
    private const val NAMED = 10

    /** Spring's own handlers and CoSec's own beans are not the application's. */
    private val NOT_THE_APPLICATIONS = listOf("org.springframework.", "me.ahoo.cosec.spring.boot.")

    /** An actuator controller endpoint is a controller too, and `STA-2` already decides whether it may be exposed. */
    private val ACTUATOR_ENDPOINTS = listOf(
        "org.springframework.boot.actuate.endpoint.web.annotation.ControllerEndpoint",
        "org.springframework.boot.actuate.endpoint.web.annotation.RestControllerEndpoint",
    )

    /** The controllers and router functions the application declares, by bean name, sorted. */
    fun find(beans: ListableBeanFactory): List<String> {
        val controllers = beans.getBeanNamesForAnnotation(Controller::class.java).filter {
            applications(beans, it, beans.getType(it, false))
        }
        val routers = beans.getBeanNamesForType(RouterFunction::class.java, true, false).filter {
            applications(beans, it, beans.getType(it, false))
        }
        return (controllers + routers).distinct().sorted()
    }

    /** False for a bean of Spring or of CoSec: judged by the class declaring its `@Bean` method, else by its own class. */
    private fun applications(beans: ListableBeanFactory, name: String, type: Class<*>?): Boolean {
        val configurable = beans as? ConfigurableListableBeanFactory ?: (beans as? ConfigurableApplicationContext)?.beanFactory
        val owner = configurable?.let { factory ->
            val definition = definitionOf { factory.getBeanDefinition(name) }
            definition?.factoryBeanName?.let { factory.getType(it, false) }
        }
        // A `@Bean` method is its declaring class's: a router function's own type is Spring's interface.
        val classes = listOfNotNull(owner ?: type)
        return classes.none { clazz ->
            NOT_THE_APPLICATIONS.any { clazz.name.startsWith(it) } ||
                ACTUATOR_ENDPOINTS.any { MergedAnnotations.from(clazz).isPresent(it) }
        }
    }

    private fun <T> definitionOf(block: () -> T): T? = try {
        block()
    } catch (_: org.springframework.beans.factory.NoSuchBeanDefinitionException) {
        null
    }

    /** The warning for [handlers], or null when there are none. */
    fun message(handlers: List<String>): String? {
        if (handlers.isEmpty()) {
            return null
        }
        val named = handlers.take(NAMED).joinToString(", ") + if (handlers.size > NAMED) ", ..." else ""
        return "This is a Spring Cloud Gateway host: CoSec decides the requests of gateway routes only. ${handlers.size} " +
            "handler(s) of the application itself are mapped before any route and are answered with no CoSec " +
            "decision (no authentication, no policy, no rate limit): $named. Move them to a service behind the " +
            "gateway, where a route decides them, or secure them in the application (CoSec does not cover them)."
    }

    fun warn(beans: ListableBeanFactory) {
        message(find(beans))?.let { line -> log.warn { line } }
    }
}
