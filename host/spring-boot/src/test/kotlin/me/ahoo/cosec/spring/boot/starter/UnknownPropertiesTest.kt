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

package me.ahoo.cosec.spring.boot.starter

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** A `cosec.*` key CoSec does not read fails startup, with what 8.0 replaced it with when it was a 7.x key. */
class UnknownPropertiesTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoSecPropertyCheckAutoConfiguration::class.java))

    private fun Throwable.messages() = generateSequence(this) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun `an unknown key fails startup`() {
        runner.withPropertyValues("cosec.pipeline.timeuot=3s").run { context ->
            context.startupFailure!!.messages().assert().contains("cosec.pipeline.timeuot")
        }
    }

    @Test
    fun `keys CoSec reads pass, maps and lists included`() {
        runner.withPropertyValues(
            "cosec.enabled=true",
            "cosec.pipeline.timeout=3s",
            "cosec.authentication.login.enabled=false",
            "cosec.authorization.snapshot.fail-closed.tenants[0]=acme",
            "cosec.authentication.login.social.registration.github.client-id=id",
            "cosec.unknown-properties=fail",
            // Read from the environment by the Kafka audit listener: declared in KafkaAuditProperties for the metadata.
            "cosec.audit.kafka.delivery=required",
        ).run { context -> context.startupFailure.assert().isNull() }
    }

    @Test
    fun `a 7_x key names what replaced it`() {
        runner.withPropertyValues(
            "cosec.authentication.enabled=false",
            "cosec.authentication.social.registration.github.client-id=id",
            "cosec.limiter.enabled=true",
            "cosec.authorization.cache.token.expire-after-write=30",
            "cosec.authorization.cache.token.expire-unit=SECONDS",
        ).run { context ->
            val message = context.startupFailure!!.messages()
            message.assert().contains("cosec.authentication.enabled -> cosec.authentication.login.enabled")
            message.assert().contains(
                "cosec.authentication.social.registration.github.client-id -> cosec.authentication.login.social."
            )
            message.assert().contains("cosec.limiter.enabled -> removed")
            message.assert().contains(
                "cosec.authorization.cache.token.expire-after-write -> cosec.authorization.cache.token.local-max-age"
            )
            message.assert().contains("cosec.authorization.cache.token.expire-unit -> removed")
        }
    }

    @Test
    fun `warn only logs`() {
        runner.withPropertyValues("cosec.unknown-properties=warn", "cosec.nothing=1").run { context ->
            context.startupFailure.assert().isNull()
        }
    }
}
