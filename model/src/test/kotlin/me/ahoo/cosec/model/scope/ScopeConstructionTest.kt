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

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.domain.JavaConstructor
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes

/**
 * Only [ScopeResolver] constructs a [Scope] (RFC 0004 §4.1, "the scope can be trusted"). Kotlin's `internal` keeps
 * other modules out; this keeps the rest of `cosec-model` out, since the JVM constructor is public.
 *
 * One more caller is allowed: [Scope]'s parameters are value classes, so Kotlin compiles its constructor to a private
 * one plus a synthetic bridge (the extra `DefaultConstructorMarker` parameter) that delegates to it. Only that bridge
 * is exempt, not the rest of [Scope].
 */
@AnalyzeClasses(packages = ["me.ahoo.cosec.model"], importOptions = [DoNotIncludeTests::class])
class ScopeConstructionTest {
    @ArchTest
    val onlyTheResolverConstructsScopes: ArchRule = classes().that().haveFullyQualifiedName(Scope::class.java.name)
        .should(onlyConstructedBy(ScopeResolver::class.java.name))

    /** A data class would add `copy`, which builds a scope from another without the resolver. */
    @ArchTest
    val scopeIsNotADataClass: ArchRule = classes().that().haveFullyQualifiedName(Scope::class.java.name)
        .should(haveNoDataClassMembers())

    private fun isBridge(origin: JavaCodeUnit): Boolean =
        origin is JavaConstructor &&
            origin.owner.isEquivalentTo(Scope::class.java) &&
            JavaModifier.SYNTHETIC in origin.modifiers &&
            origin.rawParameterTypes.lastOrNull()?.name == "kotlin.jvm.internal.DefaultConstructorMarker"

    private fun onlyConstructedBy(resolver: String) =
        object : ArchCondition<JavaClass>("be constructed only by $resolver and its own synthetic bridge") {
            override fun check(scope: JavaClass, events: ConditionEvents) {
                val calls = scope.constructorCallsToSelf
                if (calls.none { it.originOwner.name == resolver }) {
                    events.add(SimpleConditionEvent.violated(scope, "$resolver no longer constructs ${scope.name}"))
                }
                for (call in calls) {
                    val allowed = call.originOwner.name == resolver || isBridge(call.origin)
                    events.add(SimpleConditionEvent(call, allowed, call.description))
                }
            }
        }

    private fun haveNoDataClassMembers() =
        object : ArchCondition<JavaClass>("declare no copy, componentN, equals or hashCode") {
            override fun check(scope: JavaClass, events: ConditionEvents) {
                val generated = scope.methods.map { it.name }.filter {
                    it.startsWith("copy") || it.startsWith("component") || it == "equals" || it == "hashCode"
                }
                events.add(SimpleConditionEvent(scope, generated.isEmpty(), "${scope.name} declares $generated"))
            }
        }
}
