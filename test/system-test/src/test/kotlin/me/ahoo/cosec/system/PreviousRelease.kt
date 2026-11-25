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

package me.ahoo.cosec.system

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * The leg of the previous release (`previous=<tag>` of system-test.yml, RFC 0014 T): the gateway and control-plane
 * images of that release in front of the services of this commit. Every scenario of the control-host leg runs there,
 * except what holds only from a later release than the one under test: listed here, in one place, each with the release
 * it holds from and why. A listed scenario is reported as left out with that reason, never skipped silently, and runs
 * again once `previous` reaches its release. Previews are outside the compatibility promise: the leg is ready for the
 * day the previous release is the first with RFC 0014's readers (8.0.0-beta.9).
 */
object PreviousRelease {
    /** The release whose images the stack runs, or null outside that leg. */
    val underTest: String? get() = System.getProperty("cosec.system.previous")?.takeIf { it.isNotBlank() }

    /** Why a scenario holds only from [since]: what the earlier release lacks. */
    class Later(val since: String, val reason: String)

    /**
     * Scenarios (`Class` or `Class#method`) that hold only from a later release than an earlier one under test. Against
     * 8.0.0-beta.8 every entry follows from one change, the credential's kind (IDN-44 to IDN-46): this commit's writers of
     * `CoSec-Identity` state `auth.kind`, and beta.8's reader refuses a member it does not know (400), as every reader
     * before RFC 0014's did; beta.8's gateway writes no `auth.kind`, which this commit's services read as a workload's.
     * 8.0.0-beta.9 is the first release whose reader ignores a member it does not know (IDN-41).
     */
    val LATER: Map<String, Later> = mapOf(
        "ColdStartScenario#with no documents every request is unavailable and readiness refuses, while each process stays live"
            to Later(BETA_9, "it states an identity with auth.kind to the control host, whose beta.8 reader answers 400, not 503"),
        "ControlHostScenario#the operators' port, the service port and the management port each serve what they are for"
            to Later(BETA_9, "it states identities with auth.kind to the control host's operators' and service ports, refused 400"),
        "ControlHostScenario#an identity the host cannot trust is refused, be it none, doubled, expired or too old"
            to Later(BETA_9, "its stated identities carry auth.kind, refused 400 before the expiry each case is about"),
        "ControlReplicaScenario" to Later(BETA_9, "it proposes through the service port as a stated service, with auth.kind: refused 400"),
        "ControlServiceScenario" to Later(BETA_9, "every case states its caller to the service port with auth.kind: refused 400"),
        "TenantOnboardingScenario" to Later(
            BETA_9,
            "the IAM onboards through the control host's service port, and its relay states auth.kind there, refused 400; every " +
                "case works on the tenant it onboards",
        ),
        "IdentityScenario#a permitted request reaches the service with the identity the gateway verified, and the path it decided on"
            to Later(BETA_9, "it reads auth.kind of the identity the gateway forwards, which beta.8's gateway does not write"),
        "IdentityScenario#a token CoSec's login issued reaches the service with its session, which a service relies on"
            to Later(BETA_9, "it reads auth.kind of the identity the gateway forwards, which beta.8's gateway does not write"),
        "IdentityScenario#a refreshed token stays a person's, and a token without its kind is forwarded as a workload's"
            to Later(BETA_9, "it reads auth.kind of the identity the gateway forwards, which beta.8's gateway does not write"),
    )

    private const val BETA_9 = "8.0.0-beta.9"

    /** What [scenario] needs of the release under test, if anything, or null when it runs. */
    fun leftOut(scenario: String, underTest: String? = this.underTest, listed: Map<String, Later> = LATER): String? {
        val release = underTest ?: return null
        val later = listed[scenario] ?: listed[scenario.substringBefore('#')] ?: return null
        return if (Release.of(release) < Release.of(later.since)) {
            "holds from ${later.since}, not against $release: ${later.reason}"
        } else {
            null
        }
    }

    /** A release's place in the order: 8.0.0-beta.9 < 8.0.0-rc.1 < 8.0.0 < 8.0.1 < 8.1.0. */
    class Release private constructor(private val parts: List<Int>) : Comparable<Release> {
        override fun compareTo(other: Release): Int =
            parts.zip(other.parts).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a.compareTo(b) } ?: 0

        companion object {
            private val PATTERN = Regex("""(\d+)\.(\d+)\.(\d+)(?:-(beta|rc|RC)\.?(\d+))?""")
            private const val STAGE = 4
            private const val COUNT = 5
            private const val FINAL = 3

            fun of(text: String): Release {
                val groups = requireNotNull(PATTERN.matchEntire(text)) { "[$text] is not a release" }.groupValues
                val stage = when (groups[STAGE].lowercase()) {
                    "beta" -> 1
                    "rc" -> 2
                    else -> FINAL
                }
                return Release((1..3).map { groups[it].toInt() } + stage + (groups[COUNT].toIntOrNull() ?: 0))
            }
        }
    }

    /** Leaves out, with its reason, a scenario [LATER] lists for the release under test. */
    class Condition : ExecutionCondition {
        override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
            val type = context.testClass.orElse(null) ?: return ConditionEvaluationResult.enabled("not a scenario")
            val scenario = type.simpleName + context.testMethod.map { "#${it.name}" }.orElse("")
            return leftOut(scenario)?.let(ConditionEvaluationResult::disabled)
                ?: ConditionEvaluationResult.enabled("runs against ${underTest ?: "this commit"}")
        }
    }
}
