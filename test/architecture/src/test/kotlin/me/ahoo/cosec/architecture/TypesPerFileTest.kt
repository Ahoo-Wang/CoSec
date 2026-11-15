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

package me.ahoo.cosec.architecture

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * RFC 0013 §8.1: one non-private top-level type per main source file, or one family, which [TypeLedger] lists with its
 * reason; any other file with several types is a debt the ledger names with its batch.
 */
class TypesPerFileTest {
    private val types: Map<String, List<String>> = MainSources.files.filter { it.path.endsWith(".kt") }
        .associate { it.path to TopLevelTypes.of(it) }

    @Test
    fun `the parser sees the declarations it is meant to`() {
        types.getValue("core/model/src/main/kotlin/me/ahoo/cosec/model/Ids.kt").assert()
            .contains("TenantId", "PrincipalId", "Identifiers")
        types.getValue("core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/http/HttpMethods.kt").assert()
            .containsExactly("HttpMethods")
    }

    @Test
    fun `a file with several top-level types has an entry`() {
        val unlisted = types.filter { (path, names) -> names.size > 1 && path !in TypeLedger.ENTRIES }
            .map { (path, names) -> "$path: ${names.joinToString()}" }
        unlisted.assert().describedAs(
            "main source files with several non-private top-level types: give each type a file of its own, or add a " +
                "TypeLedger entry (a family by its criterion, or a debt with its batch) and have it reviewed",
        ).isEmpty()
    }

    @Test
    fun `every entry names a file that still has several types`() {
        val stale = TypeLedger.ENTRIES.keys.mapNotNull { path ->
            when (val names = types[path]) {
                null -> "$path: no such main source file"
                else -> "$path: ${names.joinToString()}".takeIf { names.size <= 1 }
            }
        }
        stale.assert().describedAs("TypeLedger entries whose file is gone or clean again: remove them").isEmpty()
    }
}
