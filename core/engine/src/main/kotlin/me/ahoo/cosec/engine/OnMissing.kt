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

package me.ahoo.cosec.engine

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * What a statement means when an external attribute its condition reads cannot be fetched (RFC 0006 §5.4.3): its
 * provider failed or timed out, or the attribute was not in the plan. Attributes that are merely absent are not missing
 * (RFC 0005 §3.4).
 */
@InternalCoSecApi
enum class OnMissing {
    /**
     * The default. A DENY errs: unless another DENY matches, the decision is `Indeterminate(AttributeUnavailable)`. An
     * ALLOW is skipped, and if nothing permits, the decision is `Indeterminate(AttributeUnavailable)` rather than the
     * implicit deny: never a Permit, only a 503 instead of a 401 or 403.
     */
    INDETERMINATE,

    /**
     * The statement does not match. On a DENY that makes the DENY lapse while a dependency fails, so a DENY compiles
     * with it only when it carries the control plane's confirmation, in any document (RFC 0007 N6, Q13; `ATR-6`). A rate
     * limit rule has no confirmation flow: only the platform document's rules may say it (`ATR-7`).
     */
    NOMATCH,
}
