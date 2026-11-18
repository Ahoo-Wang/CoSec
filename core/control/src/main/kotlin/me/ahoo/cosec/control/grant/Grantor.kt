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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.SubjectAttributes
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId

/**
 * The principal of a control-plane request, with the memberships of that request (RFC 0007 §7.4.2): always
 * authenticated. [attributes] are its token's `attributes` claim, of every JSON type, as the engine holds them; the
 * analysis reads `subject.attributes.<name>` from them through the engine's own [SubjectAttributes.string] (RC1-2).
 */
@InternalCoSecApi
class Grantor(
    val tenant: TenantId,
    val id: PrincipalId,
    val memberships: Memberships,
    val attributes: Map<String, Any>,
    /**
     * Whether what is bound to the grantor by name (attachments, principal bindings) grants it anything here: false for
     * a credential with a role cap (RFC 0009 §4.2), which does not hold those ALLOWs, and so cannot grant them either.
     * Their DENYs bind it all the same (RC1-1).
     */
    val principalBindings: Boolean,
)
