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
package me.ahoo.cosec.api.principal

/**
 * Interface for entities that have policies.
 *
 * This interface allows principals to have direct policy assignments
 * in addition to role-based permissions.
 *
 * Relationship:
 * - [CoSecPrincipal] 1:N [me.ahoo.cosec.policy.Policy]
 *
 * @see Policy
 * @see CoSecPrincipal
 */
interface PolicyCapable {
    /**
     * The set of policy IDs assigned to this principal.
     *
     * These policies are evaluated during authorization alongside
     * role-based permissions.
     *
     * @return Set of policy identifiers
     */
    val policies: Set<String>

    companion object {
        const val POLICY_KEY = "policies"
    }
}
