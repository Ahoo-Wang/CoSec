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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.InvalidPatternException
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.CheckResult
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.IndexKeys
import me.ahoo.cosec.model.decision.DocumentKind

/** A statement as a document writes it (RFC 0005 §5.1), before it is compiled. */
class StatementSource(
    val name: String,
    val effect: Effect,
    /** `service:verb`, `service:*` or `*`; at least one. */
    val actions: List<String>,
    /** Empty for any resource. */
    val resources: List<ResourceSource> = emptyList(),
    val condition: Condition? = null,
    /** What an external attribute the condition reads that cannot be fetched means; null for the default. */
    val onMissing: OnMissing? = null,
)

/** A resource pattern and the options of `http:path:` patterns (RFC 0005 §5.2). */
class ResourceSource(
    val pattern: String,
    val caseSensitive: Boolean = true,
    val decodeAndParseSegments: Boolean = true,
)

/** A reason a policy does not compile, at a JSON [path] relative to the policy, e.g. `$.statements[1].action[0]`. */
data class CompileError(val path: String, val message: String)

/** The policy [policyId] does not compile. It is never installed (RFC 0003 F4), so it never decides anything. */
class CompileException(val policyId: String, val errors: List<CompileError>) :
    IllegalArgumentException(
        "Policy [$policyId] does not compile: " + errors.joinToString("; ") { "${it.path}: ${it.message}" }
    )

/**
 * Compiles policies (RFC 0005 §6.1): parses the action and resource patterns, type-checks and bounds the conditions
 * with [checker], extracts the index keys, and reports every error with its path. Stateless and thread-safe.
 *
 * Besides what [ConditionChecker] checks, a statement's condition may read `resource.var.<name>` only if every one
 * of its resource patterns captures `<name>`, and a policy condition never: a variable a pattern does not capture
 * would read as absent, which turns a DENY written against it into a miss (RFC 0001 P6).
 */
class PolicyCompiler(
    private val checker: ConditionChecker = ConditionChecker(),
    private val options: IndexOptions = IndexOptions.DEFAULT,
) {
    /**
     * Compiles a policy, or throws [CompileException] with every error. [denyNomatch] says whether a DENY may write
     * `onMissing: nomatch`, which only the platform document may (RFC 0006 Q13).
     */
    fun compile(
        id: PolicyId,
        condition: Condition?,
        statements: List<StatementSource>,
        attachable: Boolean = false,
        denyNomatch: Boolean = false,
    ): CompiledPolicy {
        val errors = ArrayList<CompileError>()
        val checked = condition?.let { policyCondition(it, "$.condition", errors) }
        val compiled = statements.mapIndexedNotNull { index, source ->
            statement(index, source, "$.statements[$index]", errors, denyNomatch)
        }
        if (errors.isNotEmpty()) {
            throw CompileException(id.value, errors)
        }
        return CompiledPolicy(id, checked, compiled, attachable, options)
    }

    /**
     * Checks a policy condition alone, or throws [CompileException]. Policies given the same instance evaluate it
     * once per evaluation, as a converted app condition is (RFC 0004 §5.1).
     */
    fun condition(policyId: PolicyId, condition: Condition): CheckedCondition {
        val errors = ArrayList<CompileError>()
        return policyCondition(condition, "$.condition", errors) ?: throw CompileException(policyId.value, errors)
    }

    /** Compiles one statement at [index] of its policy, or throws [CompileException]. */
    fun statement(
        policyId: PolicyId,
        index: Int,
        source: StatementSource,
        denyNomatch: Boolean = false
    ): CompiledStatement {
        val errors = ArrayList<CompileError>()
        return statement(index, source, "$.statements[$index]", errors, denyNomatch)
            ?: throw CompileException(policyId.value, errors)
    }

    /**
     * Compiles a rate limit rule of the [kind] document of [owner] (RFC 0006 §8.1), or throws [CompileException].
     *
     * Its statement compiles as a DENY's does: a rule that matched less than it says would let requests escape their
     * limit, so a bare resource type does not compile, and only the platform may write `onMissing: nomatch`. Its key
     * names a text or address attribute; a `resource.var.<name>` key must be captured by every resource pattern.
     */
    fun rateLimit(kind: DocumentKind, owner: String, source: RateLimitSource): CompiledRateLimit {
        val errors = ArrayList<CompileError>()
        if (!CompiledRateLimit.isValidId(source.id)) {
            errors += CompileError("$.id", "a rate limit rule id is letters, digits, '.', '_' and '-', not [${source.id}]")
        }
        val strict = with(source.statement) {
            StatementSource(name, Effect.DENY, actions, resources, condition, onMissing)
        }
        val statement = statement(0, strict, "$", errors, denyNomatch = kind == DocumentKind.PLATFORM)
        val key = source.key?.let { path -> key(path, statement, errors) }
        if (errors.isNotEmpty()) {
            throw CompileException(source.id, errors)
        }
        val id = CompiledRateLimit.counterId(kind, owner, source.id)
        val policy = CompiledPolicy(CompiledRateLimit.policyId(id), null, listOf(statement!!), false, options)
        return CompiledRateLimit(id, policy, statement, key, source.limit, source.strictFailure)
    }

    private fun key(path: String, statement: CompiledStatement?, errors: MutableList<CompileError>): Attribute? {
        val attribute = checker.attribute(path)
        if (attribute == null) {
            errors += CompileError("$.key", "unknown attribute `$path`")
            return null
        }
        if (attribute.type !in CompiledRateLimit.KEY_TYPES) {
            errors += CompileError("$.key", "a key names a text or address attribute; `$path` is ${attribute.type}")
            return null
        }
        if (attribute.kind == Attribute.Kind.RESOURCE_VAR && statement != null &&
            statement.resources.any { attribute.name !in it.variableNames }
        ) {
            errors += CompileError("$.key", "the key reads $attribute, which a resource pattern does not capture")
            return null
        }
        return attribute
    }

    private fun policyCondition(condition: Condition, path: String, errors: MutableList<CompileError>): CheckedCondition? {
        val checked = check(condition, path, errors) ?: return null
        val variable = ConditionAttributes.of(checked).firstOrNull { it.kind == Attribute.Kind.RESOURCE_VAR }
        if (variable != null) {
            errors += CompileError(path, "a policy condition cannot read $variable: only a statement captures variables")
            return null
        }
        return checked
    }

    private fun statement(
        index: Int,
        source: StatementSource,
        path: String,
        errors: MutableList<CompileError>,
        denyNomatch: Boolean,
    ): CompiledStatement? {
        val before = errors.size
        if (source.actions.isEmpty()) {
            errors += CompileError("$path.action", "at least one action is required; write \"*\" for every action")
        }
        val actions = source.actions.mapIndexedNotNull { i, action ->
            parse("$path.action[$i]", errors) { ActionPattern.parse(action) }
        }
        val resources = source.resources.mapIndexedNotNull { i, resource ->
            parse("$path.resource[$i]", errors) {
                ResourcePattern.parse(resource.pattern, resource.caseSensitive, resource.decodeAndParseSegments)
            }
        }.ifEmpty { listOf(ResourcePattern.AnyResource) }
        if (source.effect === Effect.DENY) {
            checkBare(resources, path, errors)
        }
        val condition = source.condition?.let { check(it, "$path.condition", errors) }
        if (condition != null) {
            checkVariables(condition, resources, path, errors)
        }
        checkOnMissing(source, condition, path, errors, denyNomatch)
        if (errors.size > before) {
            return null
        }
        val keyAttributes = ArrayList<Attribute>()
        val keys = ArrayList<IndexKeys>()
        if (condition != null) {
            // Every attribute the engine reads itself, whatever the index splits by: the keys are part of what the
            // statement means (CompiledStatement), so they must not depend on the index options (I2).
            val indexable = ConditionAttributes.of(condition).filter { IndexOptions.indexable(it) }.distinct()
            for (attribute in indexable) {
                val attributeKeys = IndexKeys.of(condition, attribute)
                if (attributeKeys is IndexKeys.Only) {
                    keyAttributes += attribute
                    keys += attributeKeys
                }
            }
        }
        return CompiledStatement(
            index = index,
            name = source.name,
            effect = source.effect,
            actions = actions.toTypedArray(),
            resources = resources.toTypedArray(),
            condition = condition,
            keyAttributes = keyAttributes.toTypedArray(),
            keys = keys.toTypedArray(),
            onMissing = source.onMissing ?: OnMissing.INDETERMINATE,
        )
    }

    /**
     * `onMissing` applies to external attributes only, so a statement whose condition reads none cannot write it: it
     * would do nothing (RFC 0005 §3.4). A DENY may write `nomatch` only where [denyNomatch] allows it (RFC 0006 Q13).
     */
    private fun checkOnMissing(
        source: StatementSource,
        condition: CheckedCondition?,
        path: String,
        errors: MutableList<CompileError>,
        denyNomatch: Boolean,
    ) {
        val onMissing = source.onMissing ?: return
        if (condition == null || ConditionAttributes.of(condition).none(Attribute::external)) {
            errors += CompileError("$path.onMissing", "onMissing applies to external attributes only, and the condition reads none")
            return
        }
        if (onMissing == OnMissing.NOMATCH && source.effect === Effect.DENY && !denyNomatch) {
            errors += CompileError(
                "$path.onMissing",
                "a DENY may write onMissing: nomatch only in the platform document: it lapses while its provider fails",
            )
        }
    }

    /**
     * A bare type (`order`) matches only the resource with an empty id, while a final `**` segment matches every order: a DENY
     * written the bare way would cover far less than it reads, so it does not compile (review S3 of #1040).
     */
    private fun checkBare(resources: List<ResourcePattern>, path: String, errors: MutableList<CompileError>) {
        for ((i, resource) in resources.withIndex()) {
            if (resource is ResourcePattern.Typed && resource.bare) {
                errors += CompileError(
                    "$path.resource[$i]",
                    "a DENY on the bare type [$resource] covers only the ${resource.type} with an empty id; write " +
                        "[$resource/**] for every ${resource.type}, and add an eq on resource.id with the empty " +
                        "string for the empty id alone",
                )
            }
        }
    }

    private fun checkVariables(
        condition: CheckedCondition,
        resources: List<ResourcePattern>,
        path: String,
        errors: MutableList<CompileError>,
    ) {
        val variables = ConditionAttributes.of(condition).filter { it.kind == Attribute.Kind.RESOURCE_VAR }
        for (variable in variables.distinct()) {
            for ((i, resource) in resources.withIndex()) {
                if (variable.name !in resource.variableNames) {
                    val at = if (resource === ResourcePattern.AnyResource) "$path.resource" else "$path.resource[$i]"
                    errors += CompileError(at, "the condition reads $variable, which [$resource] does not capture")
                }
            }
        }
    }

    private fun check(condition: Condition, path: String, errors: MutableList<CompileError>): CheckedCondition? =
        when (val result = checker.check(condition)) {
            is CheckResult.Valid -> result.condition
            is CheckResult.Invalid -> {
                result.errors.forEach { errors += CompileError(path + it.path.removePrefix("$"), it.message) }
                null
            }
        }

    private inline fun <T> parse(path: String, errors: MutableList<CompileError>, parse: () -> T): T? =
        try {
            parse()
        } catch (error: InvalidPatternException) {
            errors += CompileError(path, error.message ?: "invalid pattern")
            null
        }
}
