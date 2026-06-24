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

package me.ahoo.cosec.policy.condition

import ognl.AbstractMemberAccess
import ognl.ClassResolver
import ognl.OgnlContext
import java.lang.reflect.Member
import java.lang.reflect.Modifier

/**
 * A locked-down OGNL [ognl.MemberAccess] that prevents a policy-supplied expression from reaching
 * security-sensitive members.
 *
 * OGNL's default member access only checks [Modifier.isPublic], which leaves the whole public JDK
 * surface (file I/O, reflection, `System` properties, ...) reachable from a policy expression. This
 * implementation additionally denies:
 * - non-public members (it never escalates access via `setAccessible`),
 * - the `getClass` reflection entry point (blocks `expr.class.classLoader...` gadget chains),
 * - any member declared on a denylisted class or package prefix.
 *
 * Combined with [DenyAllOgnlClassResolver] (which blocks `new X(...)`, `@X@y` and type references),
 * an OGNL condition is restricted to navigating/comparing the `request` and `context` object graphs.
 */
object SecureOgnlMemberAccess : AbstractMemberAccess() {
    private val deniedClasses: Set<Class<*>> = setOf(
        Runtime::class.java,
        ProcessBuilder::class.java,
        Process::class.java,
        System::class.java,
        Thread::class.java,
        ThreadGroup::class.java,
        Class::class.java,
        ClassLoader::class.java,
    )

    private val deniedPackagePrefixes: List<String> = listOf(
        "java.io",
        "java.nio",
        "java.net",
        "java.lang.reflect",
        "java.lang.invoke",
        "javax.naming",
        "javax.script",
        "jakarta.naming",
        "sun",
        "jdk",
        "ognl",
        "javassist",
        "groovy",
    )

    override fun isAccessible(
        context: OgnlContext,
        target: Any?,
        member: Member,
        propertyName: String?
    ): Boolean {
        if (!Modifier.isPublic(member.modifiers)) {
            return false
        }
        if (member.name == GET_CLASS_METHOD_NAME) {
            return false
        }
        return !isDenied(member.declaringClass)
    }

    private fun isDenied(declaringClass: Class<*>): Boolean {
        if (deniedClasses.contains(declaringClass)) {
            return true
        }
        val name = declaringClass.name
        return deniedPackagePrefixes.any { name.startsWith("$it.") }
    }

    private const val GET_CLASS_METHOD_NAME = "getClass"
}

/**
 * An OGNL [ClassResolver] that denies all class resolution. This blocks constructor invocations
 * (`new X(...)`), static method/field access (`@X@member`) and bare type references inside a
 * policy-supplied OGNL expression, which is where the most dangerous gadgets live.
 */
object DenyAllOgnlClassResolver : ClassResolver {
    @Throws(ClassNotFoundException::class)
    override fun <T : Any?> classForName(className: String, context: OgnlContext): Class<T> {
        throw ClassNotFoundException(
            "CoSec OGNL sandbox denies class resolution for security reasons: '$className'."
        )
    }
}
