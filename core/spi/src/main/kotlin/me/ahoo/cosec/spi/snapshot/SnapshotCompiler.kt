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

package me.ahoo.cosec.spi.snapshot

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * Compiles one snapshot document. Called once per stored revision, never on the caller's thread of
 * `SnapshotSource.load`, and its result is shared by every request until a newer revision replaces it, so it must be
 * immutable.
 *
 * A document compiles whole or not at all (RFC 0003, `DOC-1`): a compiler throws on any entry that does not compile,
 * and the document is installed as failed, so every request that needs it fails closed, whatever entry it would have
 * reached. CoSec's compiler, `PolicyDocumentCompiler`, records no per-entry failure.
 */
@InternalCoSecApi
fun interface SnapshotCompiler<out T : Any> {
    fun compile(key: SnapshotKey, document: SnapshotDocument): T
}
