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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.runtime

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * UA-5: a check refused for how it was called (a foreign context, an ended request, a malformed action) is still
 * `Deny(null)`, and says so: one WARN per kind per interval, naming the request and the page; DEBUG for every one.
 */
class CheckMisuseTest {
    private val lines = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(AuthorizationChecker::class.java.name) as Logger
    private var level: Level? = null
    private var now = 0L
    private val misuse = CheckMisuse(Duration.ofMinutes(1)) { now }
    private val pipeline = PipelineFixtures.pipeline(authenticator = authenticator(authenticated()))
    private val checker = AuthorizationChecker(pipeline, misuse)

    @BeforeEach
    fun attach() {
        level = logger.level
        logger.level = Level.DEBUG
        lines.start()
        logger.addAppender(lines)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(lines)
        logger.level = level
    }

    private fun warnings() = lines.captured().filter { it.level == Level.WARN }.map { it.formattedMessage }

    private fun debugs() = lines.captured().filter { it.level == Level.DEBUG }.map { it.formattedMessage }

    private fun permitted() = runBlocking { pipeline.evaluate(facts("/orders/1")) }.security!!

    @Test
    fun `each kind of misuse warns once per interval and is debugged every time`() = runBlocking<Unit> {
        val security = permitted()
        val foreign = mockk<SecurityContext>()
        repeat(3) {
            checker.decide(foreign, "order:read", "order/42").assert().isEqualTo(Decision.Deny(null))
            checker.decide(security.securityContext, "orderread", "order/42").assert().isEqualTo(Decision.Deny(null))
            checker.decide(security.securityContext, "http:get", "/orders/1").assert().isEqualTo(Decision.Deny(null))
        }
        security.end()
        repeat(3) {
            checker.decide(security.securityContext, "order:read", "order/42").assert().isEqualTo(Decision.Deny(null))
        }

        val warned = warnings()
        warned.assert().hasSize(3)
        warned[0].assert().contains("not one the pipeline made").contains("Request [none]").contains(CheckMisuse.PAGE)
        warned[1].assert().contains("service:verb").contains("Request [${security.requestId}]")
            .contains("action [orderread] on [order/42]")
        warned[2].assert().contains("its request has ended").contains("PIP-26").contains(security.requestId)
        // Every other occurrence at DEBUG: 9 refused checks before the end, 3 after, 3 of them warned.
        debugs().filter { it.startsWith("Check denied before any policy was read") }.assert().hasSize(9)
    }

    @Test
    fun `a kind warns again once its interval has passed`() = runBlocking<Unit> {
        val foreign = mockk<SecurityContext>()
        checker.check(foreign, "order:read", "order/42").assert().isFalse()
        now += Duration.ofSeconds(59).toNanos()
        checker.check(foreign, "order:read", "order/42").assert().isFalse()
        now += Duration.ofSeconds(1).toNanos()
        checker.check(foreign, "order:read", "order/42").assert().isFalse()
        warnings().assert().hasSize(2)
    }

    /** A resource may come from a client: one enforcement refuses stays a silent deny. */
    @Test
    fun `a resource enforcement refuses logs no warning`() = runBlocking<Unit> {
        checker.decide(permitted().securityContext, "http:GET", "/orders/%zz").assert().isEqualTo(Decision.Deny(null))
        warnings().assert().isEmpty()
    }
}
