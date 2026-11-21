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

package me.ahoo.cosec.spring.boot.control

import me.ahoo.cosec.annotations.InternalCoSecApi
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.boot.util.LambdaSafe
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.ApplicationContext
import org.springframework.http.client.ReactorResourceFactory

/**
 * The server of the control API's service port (`CTL-58`, `CTL-71`): the application's own server on another port. It is a
 * fresh [NettyReactiveWebServerFactory] built as Spring Boot builds the application's (the shared event loop, the
 * application's [NettyServerCustomizer]s) and handed every [WebServerFactoryCustomizer] of the context, the ones Boot
 * registers for `server.*` and `server.netty.*` among them, then the port. So the service port has the application's TLS
 * and client authentication (`server.ssl.*`), bind address (`server.address`), HTTP/2, header and body limits, timeouts and
 * forwarded-header handling, and whatever Boot adds later, with nothing configured twice and no property of its own. A
 * bare second Netty server would take none of them: an application that makes `only-trusted-callers-can-reach` true by
 * mutual TLS on its port would serve this one in clear text on every address.
 *
 * The application's factory bean is read, never changed: this is a second factory. Netty route providers (extra routes a
 * module adds to the application's server) are not carried over: the service port serves the control API's service profile
 * and nothing else.
 */
internal object ControlServiceServer {
    /** The factory of the service port's server. */
    fun factory(context: ApplicationContext, port: Int): NettyReactiveWebServerFactory {
        val factory = NettyReactiveWebServerFactory()
        context.getBeanProvider(ReactorResourceFactory::class.java).ifAvailable?.let(factory::setResourceFactory)
        factory.addServerCustomizers(
            *context.getBeanProvider(NettyServerCustomizer::class.java).orderedStream().toList().toTypedArray(),
        )
        applyCustomizers(factory, context.getBeanProvider(WebServerFactoryCustomizer::class.java))
        // After the customizers: they set the application's port from `server.port`.
        factory.port = port
        return factory
    }

    /**
     * What Spring Boot's `WebServerFactoryCustomizerBeanPostProcessor` does for a factory bean: each customizer whose type
     * takes this factory, in the order the context gives.
     */
    @Suppress("UNCHECKED_CAST")
    private fun applyCustomizers(
        factory: NettyReactiveWebServerFactory,
        customizers: ObjectProvider<WebServerFactoryCustomizer<*>>,
    ) {
        LambdaSafe.callbacks(
            WebServerFactoryCustomizer::class.java as Class<WebServerFactoryCustomizer<NettyReactiveWebServerFactory>>,
            customizers.orderedStream().toList() as List<WebServerFactoryCustomizer<NettyReactiveWebServerFactory>>,
            factory,
        ).invoke { customizer -> customizer.customize(factory) }
    }
}
