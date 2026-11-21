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

package me.ahoo.cosec.control.server

import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * What this image refuses to start with (`STA-10`), before any bean exists (a bean factory post-processor, which lazy
 * initialization never defers). The starter already refuses a host that
 * believes the identity without the operator's statement (`STA-7`, `STA-9`); these are the settings that would turn the
 * image into something it is not:
 *
 * - `cosec.control.enabled=false`: an application that is up and serves nothing. The gateway image is the read-only one.
 * - `cosec.jwt.enabled=true`, `cosec.api-key.enabled=true` or `cosec.authentication.login.enabled=true`: credential
 *   verification, or a login, that no request of this host uses, since the host believes the identity it is sent. Two ways to authenticate on one host is how an adopter ends up
 *   thinking requests are verified when they are not. An application of its own is the host for a control plane that
 *   verifies.
 */
@Configuration(proxyBeanMethods = false)
// A class, not an object: Spring instantiates a configuration.
@Suppress("UtilityClassWithPublicConstructor")
internal class ControlServerRefusals {
    companion object {
        const val CONTROL_KEY = "cosec.control.enabled"
        const val JWT_KEY = "cosec.jwt.enabled"
        const val API_KEY_KEY = "cosec.api-key.enabled"
        const val LOGIN_KEY = "cosec.authentication.login.enabled"

        /** Static, so Spring runs it with the post-processors, before it instantiates any configuration or bean. */
        @JvmStatic
        @Bean
        fun cosecControlServerRefusals(environment: Environment): BeanFactoryPostProcessor =
            BeanFactoryPostProcessor { check(environment) }

        /** Fails with the message that says what to change; a value neither `true` nor `false` is the starter's to refuse. */
        fun check(environment: Environment) {
            check(written(environment, CONTROL_KEY) != false) {
                "cosec-control-server runs the control plane; $CONTROL_KEY=false would leave it with nothing to serve. " +
                    "Use the gateway image for a read-only instance (STA-10)."
            }
            for (key in listOf(JWT_KEY, API_KEY_KEY, LOGIN_KEY)) {
                check(written(environment, key) != true) {
                    "cosec-control-server verifies no credential and issues no token: it believes the CoSec-Identity " +
                        "header it is sent, under the statement that nothing but the gateway and the services you name can " +
                        "reach it. $key=true would wire verification or a login that nothing here uses; leave it off, or " +
                        "run an application of your own for a control plane that verifies or signs in (STA-10)."
                }
            }
        }

        private fun written(environment: Environment, key: String): Boolean? =
            when (environment.getProperty(key)?.trim()?.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
    }
}
