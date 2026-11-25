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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** The leg of the previous release: the order of releases, and what a listed scenario needs of the one under test. */
class PreviousReleaseTest {
    @Test
    fun `releases are ordered as published`() {
        val ordered =
            listOf(
                "8.0.0-beta.8",
                "8.0.0-beta.9",
                "8.0.0-beta.10",
                "8.0.0-rc.1",
                "8.0.0-RC2",
                "8.0.0",
                "8.0.1",
                "8.1.0"
            )
        ordered.map(PreviousRelease.Release::of).zipWithNext { a, b -> a < b }.assert().containsOnly(true)
    }

    @Test
    fun `nothing is left out outside the leg, and every listed scenario is a scenario of this suite`() {
        PreviousRelease.LATER.keys.forEach { scenario ->
            PreviousRelease.leftOut(scenario, underTest = null).assert().describedAs(scenario).isNull()
            val type = Class.forName("me.ahoo.cosec.system." + scenario.substringBefore('#'))
            if ('#' in scenario) {
                type.declaredMethods.map { it.name }.assert().describedAs(
                    scenario
                ).contains(scenario.substringAfter('#'))
            }
            PreviousRelease.leftOut(
                scenario,
                PreviousRelease.LATER.getValue(scenario).since
            ).assert().describedAs(scenario).isNull()
        }
    }

    @Test
    fun `a listed scenario is left out, with its reason, against an earlier release only`() {
        val listed = mapOf("IdentityScenario" to PreviousRelease.Later("8.0.0-beta.9", "the reason"))
        PreviousRelease.leftOut("IdentityScenario#some case", "8.0.0-beta.8", listed).assert()
            .isEqualTo("holds from 8.0.0-beta.9, not against 8.0.0-beta.8: the reason")
        PreviousRelease.leftOut("IdentityScenario#some case", "8.0.0-beta.9", listed).assert().isNull()
        PreviousRelease.leftOut("IdentityScenario#some case", null, listed).assert().isNull()
    }

    @Test
    fun `an unlisted scenario runs against any release`() {
        PreviousRelease.leftOut("IdentityScenario", "8.0.0-beta.8").assert().isNull()
    }
}
