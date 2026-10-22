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

import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.parity.AuthorizationFactory
import me.ahoo.cosec.parity.DecisionParity
import me.ahoo.cosec.parity.ParityResult
import me.ahoo.cosec.parity.ParityVerdict

class PolicyParityResult(val suite: String, val case: String, val result: ParityResult)

class PolicyParityReport(val results: List<PolicyParityResult>) {
    /** Every case whose two answers are not [ParityVerdict.SAME]. */
    val differences: List<PolicyParityResult>
        get() = results.filter { it.result.verdict != ParityVerdict.SAME }

    /**
     * Throws an [AssertionError] listing every case the two implementations answer differently, with both answers.
     */
    fun assertSame() {
        val differences = differences
        if (differences.isEmpty()) {
            return
        }
        val details = differences.joinToString(separator = "\n") {
            "- ${it.suite} › ${it.case}: ${it.result.verdict}; baseline ${it.result.baseline.tuple}; " +
                "candidate ${it.result.candidate.tuple}"
        }
        throw AssertionError("${differences.size} of ${results.size} policy test cases differ:\n$details")
    }
}

/**
 * Runs every case of the suites at [locations] through both [baseline] and [candidate] and compares the answers with
 * [DecisionParity] (RFC 0002 §5.5, §8.2). Expectations are not checked: a case that fails [PolicyTestKit.run] still
 * passes here as long as both implementations answer alike, errors included.
 *
 * To run a deployment's own suites, call it from a local build of CoSec's `main` (it lives in `cosec-core`'s test
 * fixtures, which are never published) with `file:` locations, e.g. `file:/path/to/suites/` + `*-policy-test.json`.
 *
 * **Temporary**: it exists only while two implementations do and is deleted in RFC 0002 PR-6, with the legacy one.
 * [DecisionParity] stays.
 */
fun PolicyTestKit.parity(
    locations: List<String>,
    rootPrincipalMatcher: RootPrincipalMatcher = RootPrincipalMatcher.NONE,
    baseline: AuthorizationFactory = AuthorizationFactory.LEGACY,
    candidate: AuthorizationFactory = AuthorizationFactory.ENGINE
): PolicyParityReport {
    val suites = load(*locations.toTypedArray())
    require(suites.isNotEmpty()) { "No policy test suites found at $locations." }
    return PolicyParityReport(
        suites.flatMap { suite ->
            val (policyRepository, appRolePermissionRepository) = repositoriesOf(suite)
            val parity = DecisionParity(
                baseline.create(
                    policyRepository,
                    appRolePermissionRepository,
                    BlacklistChecker.NoOp,
                    rootPrincipalMatcher
                ),
                candidate.create(
                    policyRepository,
                    appRolePermissionRepository,
                    BlacklistChecker.NoOp,
                    rootPrincipalMatcher
                ),
            )
            suite.cases.map { case ->
                PolicyParityResult(
                    suite.name,
                    case.name,
                    parity.compare(case.request.toRequest(), case.principal.toPrincipal()),
                )
            }
        },
    )
}

/** See [parity]. Temporary: deleted in RFC 0002 PR-6. */
fun PolicyTestKit.parity(vararg locations: String): PolicyParityReport = parity(locations.toList())
