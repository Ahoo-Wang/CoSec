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

package me.ahoo.cosec.testkit

import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.authorization.AuthorizationDecision
import me.ahoo.cosec.authorization.DecisionAuthorization
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.parity.AuthorizationFactory
import me.ahoo.cosec.parity.ParityVerdict
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * RFC 0002 §8.2: the repository's policy test suites through `PolicyTestKit.parity`.
 */
class PolicyTestKitParityTest {
    @Test
    fun passingSuitesDecideAlike() {
        val report = PolicyTestKit.parity(
            "classpath:cosec-policy-test/orders-policy-test.json",
            "classpath:cosec-policy-test/spaces/*-policy-test.json",
        )
        report.results.size.assert().isGreaterThan(5)
        report.differences.assert().isEmpty()
        report.assertSame()
    }

    @Test
    fun failingExpectationsDoNotMatterOnlyTheTwoAnswersDo() {
        PolicyTestKit.run("classpath:cosec-policy-test/failing/*.json").failures.assert().isNotEmpty()
        PolicyTestKit.parity("classpath:cosec-policy-test/failing/*.json").assertSame()
    }

    @Test
    fun errorsAreComparedToo() {
        val report = PolicyTestKit.parity("classpath:cosec-policy-test/broken/*.json")
        report.assertSame()
        val error = report.results.single { it.case == "a matcher error" }.result
        error.bothFailed.assert().isTrue()
    }

    @Test
    fun rootFollowsTheGivenMatcher() {
        PolicyTestKit.parity(
            listOf("classpath:cosec-policy-test/orders-policy-test.json"),
            rootPrincipalMatcher = RootPrincipalMatcher.DEFAULT,
        ).assertSame()
    }

    @Test
    fun anInjectedDifferenceIsReported() {
        val allowAll = AuthorizationFactory { _, _, _, _ ->
            object : DecisionAuthorization {
                override fun decide(
                    request: Request,
                    context: SecurityContext
                ): Mono<AuthorizationDecision> = Mono.just(AuthorizationDecision(AuthorizeResult.ALLOW))
            }
        }
        val report = PolicyTestKit.parity(
            listOf("classpath:cosec-policy-test/orders-policy-test.json"),
            candidate = allowAll,
        )
        report.differences.assert().isNotEmpty()
        report.differences.all { it.result.verdict == ParityVerdict.MISMATCH }.assert().isTrue()
        val failure = assertThrows(AssertionError::class.java) { report.assertSame() }
        failure.message.assert().contains("policy test cases differ").contains("MISMATCH")
    }

    @Test
    fun failingForAnotherCauseIsADifference() {
        fun failingWith(cause: String) = AuthorizationFactory { _, _, _, _ ->
            object : DecisionAuthorization {
                override fun decide(request: Request, context: SecurityContext): Mono<AuthorizationDecision> =
                    Mono.error(IllegalArgumentException(cause))
            }
        }
        val report = PolicyTestKit.parity(
            listOf("classpath:cosec-policy-test/orders-policy-test.json"),
            baseline = failingWith("one cause"),
            candidate = failingWith("another cause"),
        )
        report.results.all { it.result.verdict == ParityVerdict.SAME }.assert().isTrue()
        report.differences.assert().hasSize(report.results.size)
        report.differences.all { it.result.failedAlikeForDifferentCauses }.assert().isTrue()
        assertThrows(AssertionError::class.java) { report.assertSame() }.message.assert()
            .contains("FAILED_ALIKE_FOR_DIFFERENT_CAUSES").contains("one cause").contains("another cause")
    }

    @Test
    fun missingLocationsFail() {
        assertThrows(IllegalStateException::class.java) { PolicyTestKit.parity("classpath:not-exists/*.json") }
    }
}
