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

package me.ahoo.cosec.api.policy

/**
 * Policy effect that determines whether an action is allowed or denied.
 *
 * This enum represents the two possible effects of a policy statement:
 * - [ALLOW]: Grants permission to perform the action
 * - [DENY]: Explicitly prohibits the action
 *
 * DENY statements take precedence over ALLOW statements during policy evaluation.
 *
 * @see Statement
 * @see Policy
 */
enum class Effect {
    /** Allows the action to be performed */
    ALLOW,

    /** Denies the action */
    DENY
}
