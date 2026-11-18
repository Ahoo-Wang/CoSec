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

package me.ahoo.cosec.spring.boot.realserver

import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.DefaultResourceLoader

/**
 * `STA-1` for the served login routes (`IDN-23`, `IDN-24`): a path that is set never does nothing. Startup fails and
 * names the property on a gateway, on a host that serves no HTTP, with login or JWT off, and for a path that is not one
 * plain absolute path; unset, nothing is served and nothing is checked.
 */
class LoginRoutesStartupTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class App

    private val others = listOf(
        "me.ahoo.cosec.kafka", "me.ahoo.cosec.social", "me.ahoo.cosec.openapi", "me.ahoo.cosec.cosid",
        "me.ahoo.cosec.opentelemetry", "me.ahoo.cosec.micrometer", "me.ahoo.cosec.ip2region",
        "org.eclipse.jetty", "io.undertow", "org.apache.catalina", "org.apache.coyote",
    )

    private fun start(
        type: WebApplicationType,
        vararg properties: String,
        gateway: Boolean = false,
        hide: List<String> = emptyList(),
    ) {
        val hidden = (if (gateway) others else others + GATEWAY_GLOBAL_FILTER) + hide
        val excluded = if (gateway) {
            emptyList()
        } else {
            ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader)
                .filter { it.startsWith("org.springframework.cloud.gateway.") }
        }
        SpringApplicationBuilder(App::class.java)
            .web(type)
            .resourceLoader(DefaultResourceLoader(FilteredClassLoader(*hidden.toTypedArray())))
            .properties(
                "server.port=0",
                "cocache.backend=memory",
                "cosec.jwt.secret=login-routes-startup-secret-login-routes",
                "cosec.authorization.routes.location=",
                "spring.main.banner-mode=off",
                "spring.autoconfigure.exclude=" + excluded.joinToString(","),
                *properties,
            )
            .run()
            .close()
    }

    private fun failure(block: () -> Unit): String {
        val thrown = assertThrows<Exception> { block() }
        return generateSequence<Throwable>(thrown) { it.cause }.joinToString(" / ") { it.message.orEmpty() }
    }

    @Test
    fun `a gateway host with a path set fails startup`() {
        failure { start(WebApplicationType.REACTIVE, "$REFRESH=/auth/refresh", gateway = true) }
            .assert().contains("$REFRESH is set on a Spring Cloud Gateway host")
    }

    @Test
    fun `a host that serves no HTTP with a path set fails startup`() {
        failure { start(WebApplicationType.NONE, "$LOGOUT=/auth/logout") }
            .assert().contains("$LOGOUT is set, but this application serves no HTTP")
    }

    @Test
    fun `login or JWT off with a path set fails startup`() {
        // Without CoSec's tokens the control plane has no revocations to read: it is left out of this host.
        val noControl = listOf("me.ahoo.cosec.control")
        failure {
            start(WebApplicationType.REACTIVE, "$REFRESH=/auth/refresh", "cosec.jwt.enabled=false", hide = noControl)
        }
            .assert().contains("$REFRESH is set, but login or JWT is off")
        failure {
            start(WebApplicationType.REACTIVE, "$REFRESH=/auth/refresh", "cosec.authentication.login.enabled=false")
        }.assert().contains("$REFRESH is set, but login or JWT is off")
    }

    @Test
    fun `a path that is not one plain absolute path fails startup`() {
        for (path in listOf("auth/refresh", "/auth/../refresh", "/auth/**", "/auth/{id}", "/auth//refresh")) {
            failure { start(WebApplicationType.REACTIVE, "$REFRESH=$path") }
                .assert().contains("$REFRESH=$path is not one plain absolute path")
        }
        failure { start(WebApplicationType.REACTIVE, "$REFRESH=/auth/session", "$LOGOUT=/auth/session") }
            .assert().contains("name one path")
    }

    @Test
    fun `unset, nothing is served and nothing is checked`() {
        start(WebApplicationType.REACTIVE)
        start(WebApplicationType.NONE)
    }

    private companion object {
        const val REFRESH = "cosec.authentication.login.refresh.path"
        const val LOGOUT = "cosec.authentication.login.logout.path"
    }
}
