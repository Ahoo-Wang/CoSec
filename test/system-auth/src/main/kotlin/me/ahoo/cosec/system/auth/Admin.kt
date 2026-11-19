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

package me.ahoo.cosec.system.auth

import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.token.TokenRevoker
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * What an operator does with the login host that no client route does: `POST /admin/revoke-subject` revokes every token a
 * principal holds (`TokenRevoker.revokeSubject`, MEM-4). The platform document permits it to the platform administrator
 * `root` only, and the gateways have no route to it: the scenarios call it on this host's own port.
 */
@RestController
class Admin(private val revoker: TokenRevoker) {
    @PostMapping("/admin/revoke-subject")
    suspend fun revokeSubject(@RequestBody body: Map<String, String>) {
        revoker.revokeSubject(TenantId(body.getValue("tenant")), PrincipalId(body.getValue("principal")))
    }
}
