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

package me.ahoo.cosec.model.scope

import com.tngtech.archunit.core.domain.JavaCall.Predicates.target
import com.tngtech.archunit.core.domain.JavaClass.Predicates.type
import com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * Only [ScopeResolver] constructs a [Scope] (RFC 0004 §4.1, "the scope can be trusted"). Kotlin's `internal` keeps
 * other modules out; this keeps the rest of `cosec-model` out, since the constructor is public in bytecode.
 *
 * [Scope] itself is exempt: its parameters are value classes, so Kotlin compiles the constructor to a private one
 * and a synthetic bridge that delegates to it.
 */
@AnalyzeClasses(packages = ["me.ahoo.cosec.model"], importOptions = [DoNotIncludeTests::class])
class ScopeConstructionTest {
    @ArchTest
    val onlyTheResolverConstructsScopes: ArchRule = noClasses()
        .that().doNotHaveFullyQualifiedName(ScopeResolver::class.java.name)
        .and().doNotHaveFullyQualifiedName(Scope::class.java.name)
        .should().callConstructorWhere(target(owner(type(Scope::class.java))))

    /** Guards the rule above against matching nothing, for example after a rename. */
    @Test
    fun `the resolver is the one caller of the constructor`() {
        val classes = ClassFileImporter().withImportOption(DoNotIncludeTests()).importPackages("me.ahoo.cosec.model")
        classes.get(Scope::class.java).constructorCallsToSelf.map { it.originOwner.name }
            .filter { it != Scope::class.java.name }.toSet()
            .assert().containsExactly(ScopeResolver::class.java.name)
    }
}
