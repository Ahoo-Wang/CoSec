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

package me.ahoo.cosec.spring.boot.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.route.RouteMap
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.engine.httpStatements
import me.ahoo.cosec.runtime.snapshot.InstallReview
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * UC-1, on each host with a route map: when a document is installed, the `http:` DENY statements and rate limit rules
 * of it that a mapped route escapes ([RouteMap.escapes]) are one WARN for that revision, with the count and the first
 * few; the whole list at DEBUG. A document with none of those statements, most tenant documents, costs a scan of its
 * statements. A revision is reviewed once: a re-install, a revalidation or a restart of the loop logs nothing again.
 * What it remembers is bounded as the source bounds its keys ([capacity]): a key it forgets, as one the source evicts,
 * is reviewed again when it is installed again.
 */
internal class RouteEscapeReview(
    private val routes: RouteMap,
    private val capacity: Int
) : InstallReview<CompiledDocument> {
    private val reviewed = object : LinkedHashMap<SnapshotKey, Long>(INITIAL, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SnapshotKey, Long>): Boolean = size > capacity
    }

    override fun installed(key: SnapshotKey, revision: Long, compiled: CompiledDocument) {
        val first = synchronized(reviewed) { (reviewed.put(key, revision) != revision) }
        if (!first) {
            return
        }
        val statements = compiled.httpStatements()
        if (statements.isEmpty()) {
            return
        }
        val escapes = routes.escapes(statements)
        if (escapes.isEmpty()) {
            return
        }
        val more = if (escapes.size > SHOWN) "; and ${escapes.size - SHOWN} more at DEBUG" else ""
        log.warn {
            "[$key] revision $revision: ${escapes.size} mapped route(s) escape its http: statements, which do not apply " +
                "to them: ${escapes.take(SHOWN).joinToString("; ")}$more. Write each statement for the mapped action too."
        }
        log.debug { "[$key] revision $revision, every escape: ${escapes.joinToString("; ")}" }
    }

    /** How many keys it remembers now; for tests. */
    internal val remembered: Int get() = synchronized(reviewed) { reviewed.size }

    companion object {
        private val log = KotlinLogging.logger {}

        /**
         * The review of a host with [routes], remembering as many keys as the source of [options] holds documents;
         * none without a route map.
         */
        fun of(routes: RouteMap?, options: SnapshotOptions): List<InstallReview<CompiledDocument>> =
            if (routes == null || routes.isEmpty) {
                emptyList()
            } else {
                listOf(
                    RouteEscapeReview(routes, options.maxApps + options.maxTenants + 1)
                )
            }

        private const val SHOWN = 5
        private const val INITIAL = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
