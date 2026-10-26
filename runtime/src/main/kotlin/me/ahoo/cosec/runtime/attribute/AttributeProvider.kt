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

package me.ahoo.cosec.runtime.attribute

import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.scope.Scope
import kotlin.time.Duration

/**
 * SPI (RFC 0006 §5.4.1): fetches the values of a set of external attributes, `ext.<provider>.<name>`.
 *
 * Like every SPI of the pipeline it must not block the calling thread: an implementation over a blocking client moves
 * the call to its own bounded executor (RFC 0006 §7.7). A failure is reported by throwing, never by leaving values out:
 * a value left out is *absent* (a comparison with it is false), a failure makes every attribute asked for *unavailable*
 * and the statements reading them follow their `onMissing`.
 */
interface AttributeProvider {
    /** Registered in the attribute catalog at startup; a policy reading any other `ext.` path does not compile. */
    val attributes: List<AttributeDeclaration>

    /** How long one [fetch] may take; null for `PipelineOptions.attributeTimeout`. Then its attributes are unavailable. */
    val timeout: Duration? get() = null

    /**
     * The values of [names], all of them declared by this provider, by path: a `String`, `Boolean`, `Set<String>` or
     * `IpAddress`, as the declared type says. A value of another type is unavailable, not absent.
     */
    suspend fun fetch(request: AttributeRequest, names: Set<String>): Map<String, Any>
}

/** One external attribute a provider declares: its [path] (`ext.<provider>.<name>`), [type] and [stage]. */
class AttributeDeclaration(val path: String, val type: AttributeType, val stage: Stage = Stage.B) {
    /**
     * When the pipeline fetches it: [A] on every request, together with the memberships and before the scope, for
     * attributes that decide what the evaluation loads (the blacklist); [B] after the scope, only when the
     * [me.ahoo.cosec.engine.flat.AttributePlan] of the request names it.
     */
    enum class Stage { A, B }

    override fun toString(): String = "AttributeDeclaration($path: $type, stage $stage)"
}

/**
 * The facts an [AttributeProvider] sees: those already settled when it is asked, never another attribute's value, so
 * providers do not depend on each other.
 */
class AttributeRequest(
    /** The principal, or the anonymous one; its tenant is the request's. */
    val subject: Subject,
    /** The resolved scope; null in stage A, which runs before it is resolved. */
    val scope: Scope?,
    val action: Action,
    val resource: Resource,
    val remoteIp: String,
    val requestId: String,
    /** Empty when the request names no device. */
    val deviceId: String,
)
