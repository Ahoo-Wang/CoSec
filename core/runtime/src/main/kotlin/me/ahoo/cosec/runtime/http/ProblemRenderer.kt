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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.annotations.ExperimentalCoSecApi

/**
 * The body of the response that refuses a request (`PIP-34`): a host framework that answers its own errors as problems
 * renders CoSec's refusals in its own catalog, so its clients meet one catalog (#1840).
 *
 * It renders the body only. The status, `Content-Type: application/problem+json`, `CoSec-Request-Id`,
 * `WWW-Authenticate`, `Retry-After` and the `RateLimit-*` headers stay CoSec's: the adapter writes them, then writes
 * the bytes [render] returns as they are, whatever the renderer would have liked. So the body must be an
 * `application/problem+json` document (RFC 9457) whose `status` is [Problem.status].
 *
 * It is given the [Problem] and nothing more: its [ProblemType] (with `status`, `slug`, `code` and `uri`), the request
 * id the `CoSec-Request-Id` header carries and the trace id. Never the decision, the policy that decided, the principal
 * or the request: what a client may learn of a refusal is what CoSec's own body says (P16).
 *
 * It runs on the request path of a refusal only, on the thread that writes the response (an event loop on WebFlux and
 * the gateway): it must not block. One that throws, or answers no bytes, is replaced by CoSec's own body for that
 * response, which is logged and counted (`cosec.problem.render.failed`): the request stays refused, with CoSec's
 * status. CoSec's own TypeScript client reads `type` (or `code`): a host that replaces both makes the client blind to
 * those errors; keep CoSec's in an extension member, as [DEFAULT]'s members are
 * ([Clients](https://cosec.ahoo.me/integration/clients)).
 */
@CoSecSpi
@ExperimentalCoSecApi
fun interface ProblemRenderer {
    /** The body of the response that refuses a request with [problem], written as it is under CoSec's status and headers. */
    fun render(problem: Problem): ByteArray

    @ExperimentalCoSecApi
    companion object {
        /** CoSec's own body, [ProblemEncoder]'s bytes: what every refusal is answered with unless a host renders its own. */
        @JvmField
        val DEFAULT: ProblemRenderer = ProblemRenderer(ProblemEncoder::encode)
    }
}
