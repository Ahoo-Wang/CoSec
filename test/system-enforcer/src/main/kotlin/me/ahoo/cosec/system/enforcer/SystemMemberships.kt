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

package me.ahoo.cosec.system.enforcer

import me.ahoo.cosec.cocache.MembershipLoader
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.spi.membership.MembershipAnswer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * API keys grant nothing of their own: a key's memberships are its principal's, from the application's
 * [MembershipLoader] (IDN-1, IDN-6). This one knows the principals of the system test: `svc-` principals hold the roles
 * their name says, in the default space, and everything else is unknown (its keys are refused).
 */
@Configuration(proxyBeanMethods = false)
class SystemMemberships {
    @Bean
    fun systemMembershipLoader() = MembershipLoader { _, principal ->
        if (principal.value.startsWith("svc-")) {
            val roles = principal.value.removePrefix("svc-").split('.').map(::RoleId).toSet()
            MembershipAnswer.Known(
                Memberships(Memberships.Source.PROVIDER, mapOf(SpaceId.DEFAULT to roles), emptyList()),
            )
        } else {
            MembershipAnswer.Unknown
        }
    }
}
