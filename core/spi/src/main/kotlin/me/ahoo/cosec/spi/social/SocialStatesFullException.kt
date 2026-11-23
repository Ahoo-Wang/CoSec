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

package me.ahoo.cosec.spi.social

/**
 * A [SocialStateStore] holds as many sign-ins in progress as it may (DR1-D-9): this one is not recorded, so the sign-in
 * it would start is refused, never started unchecked. Anyone can start a sign-in, so the states are memory an anonymous
 * caller drives; the bound keeps a flood of starts from filling the store, whose fullness would refuse every other write.
 */
class SocialStatesFullException(message: String) : IllegalStateException(message)
