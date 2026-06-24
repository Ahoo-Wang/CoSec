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
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * A locked-down OGNL [ognl.MemberAccess] that restricts a policy-supplied expression to *read-only
 * navigation and comparison* of the `request` and `context` object graphs.
 *
 * OGNL's default member access only checks [Modifier.isPublic], which leaves the whole public JVM
 * surface (file I/O, reflection, `System`, process control, ...) reachable from a policy expression,
 * and also lets an expression *mutate* the graph (e.g. `context.setAttributeValue(...)`,
 * `context.attributes.put(...)`) or escape it through an adapter's `delegate`. This implementation
 * denies:
 * - non-public members, and field/constructor access (only method-based reads are allowed);
 * - mutators — setters (`setX`) and mutating collection/map operations (`put`, `add`, `clear`, ...);
 * - the `getClass` reflection entry point and the `getDelegate` adapter escape;
 * - members declared on (or inherited from) a security-sensitive type or package.
 *
 * Combined with [DenyAllOgnlClassResolver] (which blocks `new X(...)`, `@X@y` and type references),
 * an OGNL condition cannot construct objects, call statics, reach dangerous members, or write state.
 *
 * Note: signature-based read-only enforcement is best-effort. For fully untrusted policy authors,
 * prefer the SpEL matcher, which is sandboxed via `SimpleEvaluationContext.forReadOnlyDataBinding()`.
 */
object SecureOgnlMemberAccess : AbstractMemberAccess() {
    /** Exact dangerous types, matched with [Class.isAssignableFrom] so subclasses are denied too. */
    private val deniedAssignableTypes: List<Class<*>> = listOf(
        Runtime::class.java,
        Process::class.java,
        ProcessBuilder::class.java,
        System::class.java,
        Thread::class.java,
        ThreadGroup::class.java,
        Class::class.java,
        ClassLoader::class.java,
    )

    private val deniedPackagePrefixes: List<String> = listOf(
        "java.io",
        "java.nio",
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

    /** Method names that read nothing / mutate state and must never be reachable from a condition. */
    private val deniedMethodNames: Set<String> = setOf(
        // reflection entry point and adapter escape to the raw HttpServletRequest/ServerWebExchange
        "getClass",
        "getDelegate",
        // object monitor / lifecycle
        "wait",
        "notify",
        "notifyAll",
        // mutating collection / map / list operations
        "put",
        "putAll",
        "putIfAbsent",
        "add",
        "addAll",
        "addFirst",
        "addLast",
        "remove",
        "removeAll",
        "removeIf",
        "removeFirst",
        "removeLast",
        "retainAll",
        "clear",
        "replace",
        "replaceAll",
        "merge",
        "compute",
        "computeIfAbsent",
        "computeIfPresent",
        "poll",
        "push",
        "pop",
        "offer",
        "sort",
        "fill",
        "reverse",
        "swap",
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
        if (member !is Method) {
            return false
        }
        if (member.name in deniedMethodNames || member.name.startsWith(SETTER_PREFIX)) {
            return false
        }
        return !isDeniedType(member.declaringClass)
    }

    private fun isDeniedType(declaringClass: Class<*>): Boolean {
        if (deniedAssignableTypes.any { it.isAssignableFrom(declaringClass) }) {
            return true
        }
        val name = declaringClass.name
        return deniedPackagePrefixes.any { name.startsWith("$it.") }
    }

    private const val SETTER_PREFIX = "set"
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
