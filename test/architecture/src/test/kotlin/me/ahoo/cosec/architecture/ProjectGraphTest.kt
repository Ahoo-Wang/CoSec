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

package me.ahoo.cosec.architecture

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `scripts/ci/plan.py` decides which test groups a pull request runs from a project graph it reads out of the build
 * scripts' `project(":x")` references. A group left out for want of an edge is a regression nobody ran, so the graph is
 * held to what the build itself reports (`architectureFacts`, written for [Projects.facts]).
 */
class ProjectGraphTest {
    private val graph: Map<String, Set<String>> = CiPlan.plan["graph"].properties().associate { (project, edges) ->
        project to (0 until edges.size()).map { edges[it].asString() }.toSet()
    }

    private fun facts(project: String, kind: String): List<String> = Projects.facts[project].orEmpty()
        .map { it.split(' ') }
        .filter { it[0] == kind && it[2] == "project" }
        .map { it[3] }
        .filter { it != project }

    private fun reachable(project: String): Set<String> {
        val seen = mutableSetOf<String>()
        val pending = ArrayDeque(graph.getValue(project))
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (seen.add(next)) pending.addAll(graph.getValue(next))
        }
        return seen
    }

    @Test
    fun `every project the build declares a dependency on is an edge of the planner's graph`() {
        val missing = Projects.facts.keys.flatMap { project ->
            facts(project, "declared").filter { it !in graph.getValue(project) }.map { "$project -> $it" }
        }
        missing.assert().describedAs("declared by the build, unknown to scripts/ci/plan.py").isEmpty()
    }

    @Test
    fun `every project a classpath resolves to is reachable in the planner's graph`() {
        val missing = Projects.facts.keys.flatMap { project ->
            val known = reachable(project)
            facts(project, "resolved").filter { it !in known }.map { "$project -> $it" }
        }
        missing.assert().describedAs("resolved by the build, unknown to scripts/ci/plan.py").isEmpty()
    }

    @Test
    fun `the graph has an entry for every project`() {
        graph.keys.assert().containsExactlyInAnyOrderElementsOf(ProjectListTest.PROJECTS)
    }
}
