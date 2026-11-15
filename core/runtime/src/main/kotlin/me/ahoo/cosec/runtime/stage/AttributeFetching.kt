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

package me.ahoo.cosec.runtime.stage

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.AttributePlan
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.scope.Scope
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.attribute.FetchedAttributes
import me.ahoo.cosec.runtime.call.Answer
import me.ahoo.cosec.runtime.call.Throttle
import me.ahoo.cosec.runtime.call.callPort
import me.ahoo.cosec.spi.attribute.AttributeDeclaration
import me.ahoo.cosec.spi.attribute.AttributeRequest
import java.time.Duration
import kotlin.time.Duration as KotlinDuration

/**
 * How a request's external attributes are fetched (RFC 0006 §5.4), for `ResolveMembers` (stage A), `FetchAttributes`
 * (stage B) and a check. Each provider is asked in parallel with its own timeout, and one that fails or does not answer
 * makes only its own attributes unavailable. Its failures are reported at most once per interval per provider: one can
 * repeat on every request (an address no ip2region database covers, a backend that is down), and each decision already
 * carries its cause.
 */
internal class AttributeFetching(
    val attributes: AttributeProviders,
    private val engine: FlatEngine,
    private val timeout: KotlinDuration,
) {
    private val failures = Throttle(FAILURE_INTERVAL)

    /** Where one request's attributes go: [FetchedAttributes.NONE] without providers. */
    fun fetched(): FetchedAttributes = if (attributes.isEmpty) FetchedAttributes.NONE else FetchedAttributes()

    /** Every stage-A attribute, each provider in parallel. Records failures; never throws. */
    suspend fun fetchStageA(request: AttributeRequest, fetched: FetchedAttributes) {
        val stageA = attributes.stageA
        if (stageA.size == 1) {
            fetchFrom(stageA[0], stageA[0].names, request, fetched)
            return
        }
        val answers = coroutineScope {
            stageA.map { async(start = CoroutineStart.UNDISPATCHED) { provide(it, it.names, request) } }
                .map { it.await() }
        }
        stageA.forEachIndexed { index, registered -> fetched.record(registered, registered.names, answers[index]) }
    }

    /**
     * [request] with the external values of what [fetched] holds, after fetching the planned attributes stage A did not.
     * A planned attribute no provider declares stays unavailable. Stage B, or with [everyStage] a check, which asks a
     * stage-A provider as stage A does: without the scope (#1209).
     */
    suspend fun withPlanned(
        request: FlatRequest,
        view: SnapshotView,
        fetched: FetchedAttributes,
        everyStage: Boolean,
    ): FlatRequest {
        if (fetched === FetchedAttributes.NONE) {
            // Nothing fetched reads as ExternalValues.NONE: every external attribute is missing.
            return request
        }
        fetchPlanned(request, view, fetched, everyStage)
        if (fetched.isEmpty) {
            return request
        }
        return with(
            request
        ) { FlatRequest(this.request, scope, memberships, time, attributes, fetched.toExternalValues()) }
    }

    private suspend fun fetchPlanned(
        request: FlatRequest,
        view: SnapshotView,
        fetched: FetchedAttributes,
        everyStage: Boolean,
    ) {
        if (!everyStage && !attributes.hasStageB) {
            // Only stage-A providers, the blacklist say, and stage A asked them for everything: no plan to make.
            return
        }
        if (request.request.environment.blocked) {
            // The blacklist rule decides; nothing else would be read.
            return
        }
        val plan = engine.plan(request, view)
        if (plan.isEmpty) {
            return
        }
        val asked = unasked(plan, fetched) ?: return
        val stageB = attributeRequest(request, request.scope)
        // Only a check gets here with a stage-A provider: a request's stage A asked for all of them already.
        val stageA = if (everyStage) attributeRequest(request, null) else stageB
        fun requestOf(registered: AttributeProviders.Registered) =
            if (registered.stage == AttributeDeclaration.Stage.A) stageA else stageB
        if (asked.size == 1) {
            val (registered, names) = asked.entries.first()
            fetchFrom(registered, names, requestOf(registered), fetched)
            return
        }
        val entries = asked.entries.toList()
        val answers = coroutineScope {
            entries.map { (registered, names) ->
                async(start = CoroutineStart.UNDISPATCHED) { provide(registered, names, requestOf(registered)) }
            }.map { it.await() }
        }
        entries.forEachIndexed { index, (registered, names) -> fetched.record(registered, names, answers[index]) }
    }

    /** The attributes of [plan] not asked for yet, by the provider that declares them; null when there are none. */
    private fun unasked(
        plan: AttributePlan,
        fetched: FetchedAttributes,
    ): LinkedHashMap<AttributeProviders.Registered, MutableSet<String>>? {
        var groups: LinkedHashMap<AttributeProviders.Registered, MutableSet<String>>? = null
        for (attribute in plan.attributes) {
            val path = attribute.path
            if (fetched.asked(path)) {
                continue
            }
            val registered = attributes.registered(path) ?: continue
            val names = (groups ?: LinkedHashMap<AttributeProviders.Registered, MutableSet<String>>().also { groups = it })
                .getOrPut(registered) { LinkedHashSet() }
            names += path
        }
        return groups
    }

    private fun attributeRequest(request: FlatRequest, scope: Scope?) = AttributeRequest(
        request.scope.tenant,
        request.request.subject,
        scope,
        request.request.action,
        request.request.resource,
        request.request.environment.remoteIp,
        request.request.environment.requestId,
        request.request.environment.deviceId,
    )

    private suspend fun fetchFrom(
        registered: AttributeProviders.Registered,
        names: Set<String>,
        request: AttributeRequest,
        fetched: FetchedAttributes,
    ) {
        fetched.record(registered, names, provide(registered, names, request))
    }

    /** What [registered] answered for [names]; null when it failed or did not answer in time. */
    private suspend fun provide(
        registered: AttributeProviders.Registered,
        names: Set<String>,
        request: AttributeRequest,
    ): Map<String, Any>? {
        val within = registered.provider.timeout ?: timeout
        return when (val answer = callPort(within) { registered.provider.fetch(request, names) }) {
            is Answer.Value -> answer.value
            is Answer.Failed -> {
                if (failures.allow(registered.provider)) {
                    requestPathLog.warn(answer.error) {
                        "${registered.provider} failed to fetch $names. Reported at most once every " +
                            "$FAILURE_INTERVAL per provider; each decision it affects carries the cause " +
                            "(AttributeUnavailable)."
                    }
                }
                null
            }
        }
    }

    private companion object {
        val FAILURE_INTERVAL: Duration = Duration.ofSeconds(10)
    }
}
