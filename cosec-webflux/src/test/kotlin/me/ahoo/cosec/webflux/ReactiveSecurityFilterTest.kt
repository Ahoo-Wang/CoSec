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

package me.ahoo.cosec.webflux

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.jwt.InjectSecurityContextParser
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.core.publisher.toMono

class ReactiveSecurityFilterTest {
    /** A third-party subclass built with the pre-pipeline constructor. */
    private class LegacyFilter(authorization: Authorization) : ReactiveSecurityFilter(
        InjectSecurityContextParser,
        ReactiveRequestParser(ReactiveRemoteIpResolver),
        authorization,
        Schedulers.boundedElastic(),
    )

    @Test
    fun legacyConstructorBuildsPipeline() {
        val authorization = Authorization { _, _ -> AuthorizeResult.ALLOW.toMono() }
        val filter = LegacyFilter(authorization)
        filter.authorization.assert().isSameAs(authorization)
        filter.securityContextParser.assert().isSameAs(InjectSecurityContextParser)
        filter.requestParser.assert().isSameAs(filter.pipeline.requestParser)
        filter.pipeline.securityContextParseScheduler.assert().isSameAs(Schedulers.boundedElastic())
    }

    @Test
    fun filterInternalProceedsWhenAllowed() {
        val filter = LegacyFilter { _, _ -> AuthorizeResult.ALLOW.toMono() }
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/path"))
        var proceeded = false
        filter.filterInternal(exchange) { _, request ->
            request.path.assert().isEqualTo("/path")
            proceeded = true
            Mono.empty()
        }.block()
        proceeded.assert().isTrue()
    }

    @Test
    fun filterInternalRejectsWhenDenied() {
        val filter = LegacyFilter { _, _ -> AuthorizeResult.IMPLICIT_DENY.toMono() }
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/path"))
        filter.filterInternal(exchange) { _, _ -> error("must not proceed") }.block()
        exchange.response.statusCode.assert().isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
