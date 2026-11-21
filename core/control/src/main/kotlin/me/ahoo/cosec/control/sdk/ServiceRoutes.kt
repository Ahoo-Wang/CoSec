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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttpRequest
import me.ahoo.cosec.control.api.ControlHttpSupport
import me.ahoo.cosec.control.api.unescaped
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.InvalidControlRequestException
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import tools.jackson.core.JacksonException

/**
 * The routes a [ControlService] reaches (`CTL-65`, design §0.8): reads of a tenant's or an app's document, and the
 * change flow of a tenant's document. Every other route, and any of these on the platform document, is not reachable
 * through the service at all: the operators' routes are a person's. A second guard beside the service's own methods,
 * which build only these.
 */
internal object ServiceRoutes {
    private val TRANSITIONS = setOf("rebase", "skip-shadow", "abandon", "publish")

    fun allows(request: ControlHttpRequest): Boolean {
        val segments = request.path.removePrefix("/").split('/')
        return when (request.method) {
            ControlHttpSupport.GET -> reads(segments, request)
            ControlHttpSupport.POST -> writes(segments, request)
            else -> false
        }
    }

    private fun reads(segments: List<String>, request: ControlHttpRequest): Boolean = when (segments.firstOrNull()) {
        "documents" -> segments.size in 2..4 && readable(key(segments[1])) && when (segments.size) {
            2 -> true
            3 -> segments[2] == "revisions" || segments[2] == "diff"
            else -> segments[2] == "revisions"
        }
        "changes" -> when (segments.size) {
            1 -> readable(request.query["document"]?.let(::keyOf))
            2 -> ControlHttpSupport.verbOf(segments[1]).second == null
            else -> false
        }
        else -> false
    }

    private fun writes(segments: List<String>, request: ControlHttpRequest): Boolean = when (segments.firstOrNull()) {
        "documents" -> segments.size == 2 && ControlHttpSupport.verbOf(segments[1]).let { (key, verb) ->
            verb == "apply" && key(key) is SnapshotKey.Tenant
        }
        "changes" -> when (segments.size) {
            1 -> bodyDocument(request) is SnapshotKey.Tenant
            2 -> ControlHttpSupport.verbOf(segments[1]).second in TRANSITIONS
            else -> false
        }
        else -> false
    }

    private fun readable(key: SnapshotKey?): Boolean = key is SnapshotKey.Tenant || key is SnapshotKey.App

    private fun key(segment: String): SnapshotKey? = try {
        keyOf(unescaped(segment))
    } catch (_: InvalidControlRequestException) {
        null
    }

    private fun keyOf(text: String): SnapshotKey? = try {
        ChangeRequestCodec.documentKeyOf(text)
    } catch (_: SnapshotFormatException) {
        null
    }

    private fun bodyDocument(request: ControlHttpRequest): SnapshotKey? = try {
        request.body?.let { CoSecJson.mapper.readTree(it).get("document") }?.takeIf { it.isString }?.asString()
            ?.let(::keyOf)
    } catch (_: JacksonException) {
        null
    }
}
