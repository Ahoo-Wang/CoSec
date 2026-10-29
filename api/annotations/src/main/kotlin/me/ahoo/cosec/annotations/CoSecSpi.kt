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
 * An interface applications implement, not only call: an extension point (`wiki/architecture/extending.md`) or a
 * capability their own types declare. A new abstract member or a changed signature breaks every implementation, so after
 * GA it is a breaking change; a member added in a minor release comes with a default body.
 *
 * A marker for readers and `test/architecture`, which requires it on every public interface of `cosec-spi` and on
 * every extension point extending.md lists. It asks nothing of implementers (RFC 0011 D4).
 */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class CoSecSpi
