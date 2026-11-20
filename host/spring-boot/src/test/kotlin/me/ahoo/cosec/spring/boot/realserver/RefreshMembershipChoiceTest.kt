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

package me.ahoo.cosec.spring.boot.realserver

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.login.token.MembershipRefresh
import me.ahoo.cosec.login.token.RefreshTokenAuthentication
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spring.boot.CoSecModuleCheckAutoConfiguration
import me.ahoo.cosec.spring.boot.authentication.route.CoSecLoginRoutesAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecGatewayModeAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineIntegrationAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineWebAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.DefaultResourceLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * `STA-8`: a host that serves the refresh route says how a refreshed token gets its memberships, or does not start. With no
 * `MembershipRefresh` bean the starter's refresh would copy the memberships of the token it replaces, so a role removed from a
 * member would keep working until their next sign-in: the operator writes that choice (`cosec.jwt.session.memberships-on-refresh`)
 * or declares the bean that reads the member's roles and attached policies again. Run on a real Spring MVC and a real WebFlux host; a host that
 * does not serve the route is not asked.
 */
@ExtendWith(OutputCaptureExtension::class)
class RefreshMembershipChoiceTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class App

    /** The application reads the member's roles again at each refresh. */
    @Configuration(proxyBeanMethods = false)
    class WithMembershipBean {
        @Bean
        fun membershipRefresh(): MembershipRefresh<RefreshTokenCredentials> = MembershipRefresh { _, _ -> null }
    }

    /** The application builds its own refresh, which writes its choice itself (`IDN-27`). */
    @Configuration(proxyBeanMethods = false)
    class WithOwnRefresh {
        @Bean
        fun ownRefresh(
            verifier: TokenVerifier,
            revocation: TokenRevocation,
            families: TokenFamilies,
        ): RefreshTokenAuthentication<RefreshTokenCredentials> =
            RefreshTokenAuthentication(verifier, revocation, families, MembershipRefresh.CLAIMS)
    }

    private val others = listOf(
        "me.ahoo.cosec.kafka",
        "me.ahoo.cosec.social",
        "me.ahoo.cosec.openapi",
        "me.ahoo.cosec.cosid",
        "me.ahoo.cosec.opentelemetry",
        "me.ahoo.cosec.micrometer",
        "me.ahoo.cosec.ip2region",
    )

    private fun start(
        stack: ServedStack,
        vararg properties: String,
        extra: List<Class<*>> = emptyList(),
        gateway: Boolean = false,
    ) {
        val hidden = others + stack.hidden + (if (gateway) emptyList() else listOf(GATEWAY_GLOBAL_FILTER))
        val excluded = if (gateway) {
            emptyList()
        } else {
            ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader)
                .filter { it.startsWith("org.springframework.cloud.gateway.") }
        }
        SpringApplicationBuilder(App::class.java, *extra.toTypedArray())
            .web(stack.type)
            .resourceLoader(DefaultResourceLoader(FilteredClassLoader(*hidden.toTypedArray())))
            .properties(
                "server.port=0",
                "cocache.backend=memory",
                "cosec.jwt.secret=refresh-membership-choice-secret-refresh-membership",
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

    @ParameterizedTest
    @EnumSource(ServedStack::class)
    fun `no bean and no statement fails startup, and the message says what is at stake and the two ways out`(
        stack: ServedStack,
    ) {
        val message = failure {
            start(
                stack,
                "$REFRESH=/auth/refresh",
                "cosec.jwt.session.lifetime=14d",
                "cosec.jwt.token-validity.refresh=5d",
                "cosec.jwt.token-validity.access=5m",
            )
        }
        message.assert()
            // 1. what happens without a choice, with the properties as configured
            .contains("$REFRESH=/auth/refresh serves the refresh route")
            .contains("a role or an attached policy removed from a member keeps working until that member signs in again")
            .contains("up to cosec.jwt.session.lifetime=14d")
            .contains("cosec.jwt.token-validity.refresh=5d")
            // 2. the two ways to choose
            .contains("declare a MembershipRefresh<RefreshTokenCredentials> bean")
            .contains("within the access token's lifetime (cosec.jwt.token-validity.access=5m)")
            .contains("state that refreshed tokens keep the memberships of the token they replace")
            // 3. revokeSubject, then the exact line and the link
            .contains("TokenRevoker.revokeSubject ends a member's tokens at once")
            .contains("cosec.jwt.session.memberships-on-refresh=from-token")
            .contains("COSEC_JWT_SESSION_MEMBERSHIPS_ON_REFRESH=from-token")
            .contains("It is not set")
            .contains("(STA-8, https://cosec.ahoo.me/integration/authentication#how-long-a-removed-role-lives)")
        listOf(
            "keeps working until that member signs in again",
            "declare a MembershipRefresh",
            "TokenRevoker.revokeSubject",
            "cosec.jwt.session.memberships-on-refresh=from-token",
        ).map { message.indexOf(it) }.assert().isSorted()
    }

    @Test
    fun `durations are written as an operator writes them`() {
        fun message(lifetime: String, refresh: String, access: String) = failure {
            start(
                ServedStack.NETTY,
                "$REFRESH=/auth/refresh",
                "cosec.jwt.session.lifetime=$lifetime",
                "cosec.jwt.token-validity.refresh=$refresh",
                "cosec.jwt.token-validity.access=$access",
            )
        }
        message("36h", "100h", "15m").assert()
            .contains("cosec.jwt.session.lifetime=36h")
            .contains("cosec.jwt.token-validity.refresh=100h")
            .contains("cosec.jwt.token-validity.access=15m")
        message("2d", "4d", "90s").assert().contains("cosec.jwt.token-validity.access=90s")
        message("2d", "4d", "1500ms").assert().contains("cosec.jwt.token-validity.access=PT1.5S")
    }

    @ParameterizedTest
    @EnumSource(ServedStack::class)
    fun `the statement starts the host, in any letter case, with one INFO line that says the lifetime it implies`(
        stack: ServedStack,
        output: CapturedOutput,
    ) {
        start(stack, "$REFRESH=/auth/refresh", "$STATEMENT=from-token", "cosec.jwt.session.lifetime=14d")
        output.all.assert()
            .contains(
                "cosec.jwt.session.memberships-on-refresh=from-token: a token the refresh route issues keeps the memberships"
            )
            .contains("cosec.jwt.session.lifetime=14d at the latest (STA-8)")
        start(stack, "$REFRESH=/auth/refresh", "$STATEMENT=FROM-Token")
    }

    @ParameterizedTest
    @EnumSource(ServedStack::class)
    fun `a MembershipRefresh bean starts the host without the statement, and logs nothing about it`(
        stack: ServedStack,
        output: CapturedOutput,
    ) {
        start(stack, "$REFRESH=/auth/refresh", extra = listOf(WithMembershipBean::class.java))
        output.all.assert().doesNotContain("STA-8")
    }

    @ParameterizedTest
    @EnumSource(ServedStack::class)
    fun `every other value fails like an absent one, and the message says what was written`(stack: ServedStack) {
        for (written in listOf("claims", "true", "keep", "from-token ", "from_token", "lookup")) {
            failure { start(stack, "$REFRESH=/auth/refresh", "$STATEMENT=$written") }
                .assert().contains("It is [$written]").contains("(STA-8,")
        }
        failure { start(stack, "$REFRESH=/auth/refresh", "$STATEMENT=") }.assert().contains("It is []")
    }

    @ParameterizedTest
    @EnumSource(ServedStack::class)
    fun `an application's own refresh authentication has chosen already`(stack: ServedStack, output: CapturedOutput) {
        start(stack, "$REFRESH=/auth/refresh", extra = listOf(WithOwnRefresh::class.java))
        output.all.assert().doesNotContain("STA-8")
    }

    @ParameterizedTest
    @EnumSource(ServedStack::class)
    fun `a host that does not serve the refresh route is not asked`(stack: ServedStack) {
        // Nothing served; only logout (no refresh to give roles); login or JWT off (nothing issued); no login at all.
        start(stack)
        start(stack, "cosec.authentication.login.logout.path=/auth/logout")
        start(stack, "cosec.authentication.login.enabled=false")
    }

    @Test
    fun `a gateway host with the path set is told about the gateway, not asked for the choice`() {
        failure { start(ServedStack.NETTY, "$REFRESH=/auth/refresh", gateway = true) }
            .assert().contains("$REFRESH is set on a Spring Cloud Gateway host").doesNotContain("STA-8")
    }

    @Test
    fun `login or JWT off with the path set is told about that, not asked for the choice`() {
        failure { start(ServedStack.NETTY, "$REFRESH=/auth/refresh", "cosec.authentication.login.enabled=false") }
            .assert().contains("login or JWT is off").doesNotContain("STA-8")
    }

    @Test
    fun `a service in gateway mode serves no refresh and is not asked`() {
        val gateway = WebApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    CoSecPipelineIntegrationAutoConfiguration::class.java,
                    CoSecPipelineAutoConfiguration::class.java,
                    CoSecGatewayModeAutoConfiguration::class.java,
                    CoSecPipelineWebAutoConfiguration::class.java,
                    CoSecModuleCheckAutoConfiguration::class.java,
                    CoSecLoginRoutesAutoConfiguration::class.java,
                )
            )
            .withPropertyValues(
                "cosec.pipeline.mode=gateway",
                "cosec.jwt.enabled=false",
                "cosec.pipeline.identity.only-gateway-can-reach=true",
            )
        gateway.run { it.startupFailure.assert().isNull() }
        gateway.withPropertyValues("$REFRESH=/auth/refresh").run { context ->
            val message = generateSequence<Throwable>(context.startupFailure) {
                it.cause
            }.joinToString(" | ") { it.message.orEmpty() }
            message.assert().contains("$REFRESH is set").doesNotContain("STA-8")
        }
    }

    @Test
    fun `the link of the message reaches a heading of the page, in both languages`() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("wiki/integration/authentication.md")) }
        Files.readString(root.resolve("wiki/integration/authentication.md"))
            .assert().contains("## How long a removed role lives")
        Files.readString(root.resolve("wiki/zh/integration/authentication.md"))
            .assert().contains("{#how-long-a-removed-role-lives}")
    }

    private companion object {
        const val REFRESH = "cosec.authentication.login.refresh.path"
        const val STATEMENT = "cosec.jwt.session.memberships-on-refresh"
    }
}
