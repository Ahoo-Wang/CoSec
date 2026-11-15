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

import com.tngtech.archunit.core.domain.JavaClass
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * RFC 0013 §3.3: inside `cosec-runtime`, `cosec-control` and `cosec-document`, a package uses only the packages
 * [PackageLedger] allows it, or a listed debt; the allowed edges form no cycle. Read from the compiled classes.
 */
class PackageDependencyTest {
    private fun packageOf(module: PackageLedger.Module, javaClass: JavaClass): String? {
        val name = javaClass.packageName
        return when {
            name == module.base -> PackageLedger.ROOT
            name.startsWith("${module.base}.") -> name.removePrefix("${module.base}.").substringBefore('.')
            else -> null
        }
    }

    /** Each edge between two packages of [module], with the classes that make it. */
    private fun edges(module: PackageLedger.Module): Map<Pair<String, String>, Set<String>> {
        val edges = HashMap<Pair<String, String>, MutableSet<String>>()
        SourceRulesTest.CLASSES.forEach { origin ->
            val from = packageOf(module, origin) ?: return@forEach
            origin.directDependenciesFromSelf.forEach { dependency ->
                val to = packageOf(module, dependency.targetClass)
                if (to != null && to != from) {
                    edges.getOrPut(from to to) { sortedSetOf() } += "${origin.simpleName} -> ${dependency.targetClass.simpleName}"
                }
            }
        }
        return edges
    }

    @Test
    fun `every module's packages are seen`() {
        PackageLedger.MODULES.forEach { module ->
            edges(module).assert().describedAs("no package edges in ${module.name}: the rule would check nothing")
                .isNotEmpty()
        }
    }

    @Test
    fun `a package uses only the packages its module's table allows`() {
        val violations = PackageLedger.MODULES.flatMap { module ->
            val debt = module.debt.map { it.from to it.to }.toSet()
            edges(module).filterKeys { (from, to) -> to !in module.allowed[from].orEmpty() && (from to to) !in debt }
                .map { (edge, uses) ->
                    "${module.name}: ${edge.first} -> ${edge.second} (${uses.take(
                        3
                    ).joinToString()})"
                }
        }
        violations.assert().describedAs(
            "package edges PackageLedger does not allow: move the code to the package it belongs to, or change the " +
                "table (an edge that keeps the table free of cycles) and have it reviewed",
        ).isEmpty()
    }

    @Test
    fun `a debt edge still exists`() {
        val gone = PackageLedger.MODULES.flatMap { module ->
            val edges = edges(module).keys
            module.debt.filterNot { (it.from to it.to) in edges }.map { "${module.name}: ${it.from} -> ${it.to}" }
        }
        gone.assert().describedAs("debt edges in PackageLedger the code no longer has: remove them").isEmpty()
    }

    @Test
    fun `the allowed edges of a module form no cycle`() {
        val cyclic = PackageLedger.MODULES.filter { module -> hasCycle(module.allowed) }.map { it.name }
        cyclic.assert().describedAs("modules whose table in PackageLedger has a cycle").isEmpty()
    }

    @Test
    fun `cosec-document has no debt edge`() {
        PackageLedger.MODULES.single { it.name == "document" }.debt.assert().isEmpty()
    }

    private fun hasCycle(graph: Map<String, Set<String>>): Boolean {
        val done = HashSet<String>()
        val path = HashSet<String>()
        fun visit(node: String): Boolean {
            if (node in path) return true
            if (!done.add(node)) return false
            path += node
            val cycle = graph[node].orEmpty().any(::visit)
            path -= node
            return cycle
        }
        return graph.keys.any(::visit)
    }
}
