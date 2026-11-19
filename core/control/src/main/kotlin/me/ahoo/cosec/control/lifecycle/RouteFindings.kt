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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ControlStatus.UNPROCESSABLE
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.change.Severity
import me.ahoo.cosec.document.route.RouteMap
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.engine.httpStatements

/**
 * UC-1: the `ROUTES` warning of a candidate whose `http:` DENY statements or rate limit rules a mapped route of the
 * services escapes ([RouteMap.escapes]). One finding, with the count and the first few; nothing for a candidate with no
 * such statement. Without route maps it says so, so that no list is never read as nothing escaping.
 */
internal object RouteFindings {
    private const val CHECK = "ROUTES"
    private const val SHOWN = 5

    fun check(compiled: CompiledDocument, routes: RouteMap?, findings: MutableList<Finding>) {
        val statements = compiled.httpStatements()
        if (statements.isEmpty()) {
            return
        }
        if (routes == null) {
            findings += warning(
                "no route maps were given (cosec.control.analysis.route-maps): the candidate's http: DENY statements " +
                    "and rate limit rules are not compared with the services' routes, so a mapped route that escapes " +
                    "one is not listed",
            )
            return
        }
        val escapes = routes.escapes(statements)
        if (escapes.isEmpty()) {
            return
        }
        val more = if (escapes.size > SHOWN) "; and ${escapes.size - SHOWN} more" else ""
        findings += warning(
            "${escapes.size} mapped route(s) escape http: statements of this candidate, which do not apply to them: " +
                escapes.take(SHOWN).joinToString("; ") + more + ". Write each statement for the mapped action too",
        )
    }

    private fun warning(message: String) = Finding(CHECK, "$", message, UNPROCESSABLE, Severity.WARNING)
}
