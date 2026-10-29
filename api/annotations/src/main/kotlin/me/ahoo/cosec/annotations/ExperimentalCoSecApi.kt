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

package me.ahoo.cosec.annotations

/**
 * Public and in the ABI dump, but not yet stable: it may change or go away in a minor release, which the release notes
 * say, without counting as a breaking change. Using it is a warning until the caller opts in with
 * `@OptIn(ExperimentalCoSecApi::class)` (RFC 0011 D4).
 */
@RequiresOptIn(
    message = "Experimental CoSec API: it may change or go away in a minor release.",
    level = RequiresOptIn.Level.WARNING,
)
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class ExperimentalCoSecApi
