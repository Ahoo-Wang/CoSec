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

package me.ahoo.cosec.login.token

import me.ahoo.cosec.annotations.CoSecSpi

/**
 * SPI: what a signed-in user holds in the tenant it switches to ([SwitchTenantAuthentication]), and in the app it asks
 * for there, looked up for that tenant and app, never copied from the token it switches from. [C] is the application's
 * own credentials class, so the switch reads what else its decision needs (a device id, a header) without a cast.
 *
 * An exception refuses the switch: check here that the user belongs to the tenant, and has the app there. The answer's
 * [TenantMembership.app] is the app the new token is for; leave it null to keep the previous token's.
 */
@CoSecSpi
fun interface TenantSwitch<C : SwitchTenantCredentials> {
    suspend fun switchTenant(credentials: C): TenantMembership
}
