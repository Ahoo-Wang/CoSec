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

package me.ahoo.cosec.spring.boot.starter.authorization

import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.jwt.InjectSecurityContextParser
import me.ahoo.cosec.spring.boot.starter.jwt.ConditionalOnTokenRevocationEnabled
import me.ahoo.cosec.webflux.ReactiveRemoteIpResolver
import me.ahoo.cosec.webflux.ReactiveRequestParser
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.toMono

class SecurityContextParseSchedulersTest {

    @Test
    fun immediateWhenTokenRevocationDisabled() {
        MockEnvironment().securityContextParseScheduler().assert().isSameAs(Schedulers.immediate())
    }

    @Test
    fun boundedElasticWhenTokenRevocationEnabled() {
        val environment = MockEnvironment().withProperty(ConditionalOnTokenRevocationEnabled.ENABLED_KEY, "true")
        environment.securityContextParseScheduler().assert().isSameAs(Schedulers.boundedElastic())
    }

    @Test
    fun reactiveAuthorizationFilterUsesSelectedScheduler() {
        val environment = MockEnvironment().withProperty(ConditionalOnTokenRevocationEnabled.ENABLED_KEY, "true")
        val filter = CoSecAuthorizationAutoConfiguration.WebFlux().reactiveAuthorizationFilter(
            InjectSecurityContextParser,
            ReactiveRequestParser(ReactiveRemoteIpResolver),
            { _, _ -> AuthorizeResult.ALLOW.toMono() },
            environment,
        )
        filter.pipeline.securityContextParseScheduler.assert().isSameAs(Schedulers.boundedElastic())
    }
}
