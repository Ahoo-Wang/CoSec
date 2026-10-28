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

package me.ahoo.cosec.migrate.v7.policy

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.migrate.v7.api.configuration.Configuration
import me.ahoo.cosec.migrate.v7.api.context.request.Request
import me.ahoo.cosec.migrate.v7.api.permission.AppPermission
import me.ahoo.cosec.migrate.v7.api.policy.ActionMatcher
import me.ahoo.cosec.migrate.v7.api.policy.ConditionMatcher
import me.ahoo.cosec.migrate.v7.api.policy.Policy
import me.ahoo.cosec.migrate.v7.api.policy.Statement
import me.ahoo.cosec.migrate.v7.api.policy.VerifyResult
import me.ahoo.cosec.migrate.v7.policy.action.CompositeActionMatcher
import me.ahoo.cosec.migrate.v7.policy.action.PATH_VARIABLES_KEY
import me.ahoo.cosec.migrate.v7.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.migrate.v7.policy.action.clearPathVariables
import me.ahoo.cosec.migrate.v7.policy.condition.part.RequestParts

/**
 * Verifies this statement (or permission) seeing only the path variables its own action captures.
 */
internal fun Statement.verifyScoped(request: Request, securityContext: SecurityContext): VerifyResult {
    action.clearPathVariablesBeforeMatch(securityContext)
    return verify(request, securityContext)
}

/**
 * Clears the path variables before [this] action matches, unless it is a `path` action: one either fails to match,
 * so the condition never runs, or overwrites the variables. Skipping the write keeps the hot path free of a context
 * update per statement. A `path` action with a `#{...}` template reads the context before it matches, so it clears
 * the variables itself before evaluating the template (see [me.ahoo.cosec.policy.action.ReplaceablePathActionMatcher]).
 */
internal fun ActionMatcher.clearPathVariablesBeforeMatch(securityContext: SecurityContext) {
    if (type != PathActionMatcherFactory.TYPE) {
        securityContext.clearPathVariables()
    }
}

/**
 * A policy, permission or rate limit rule that reads path variables where none of its own are captured.
 *
 * Path variables belong to the statement (or rule) whose `path` action captured them, and are cleared before every
 * statement matches its action. A reader outside that scope could only ever see an empty value, or, before 7.0,
 * the variables another statement left behind; either way it is a policy error, reported when the policy loads.
 */
internal class PathVariableScopeException(message: String) : IllegalArgumentException(message)

/**
 * Load-time check that `request.path.var.*` is only read where the reader's own `path` action captured it.
 *
 * A condition or key *reads path variables* when its configuration, at any depth, holds a string that starts with
 * [RequestParts.PATH_VAR_PREFIX] (the `part` of every value condition, or a rate limit `key`) or mentions
 * [PATH_VARIABLES_KEY] (the context attribute an OGNL or SpEL expression would read). Expressions that build
 * the attribute name at run time cannot be recognized here. In a condition of a statement whose action captures no
 * variables they read nothing, because the variables are cleared before that statement matches its action (see
 * [clearPathVariablesBeforeMatch]). In a condition of a statement with a `path` action they read that action's own
 * variables.
 *
 * An action never reads path variables: a `path` template (`#{...}`) is evaluated before the action matches and
 * captures anything, so an action whose configuration mentions [RequestParts.PATH_VAR_PREFIX] or
 * [PATH_VARIABLES_KEY] anywhere is rejected. A template that builds the name at run time escapes this check, so the
 * template matcher clears the variables before it evaluates: it reads nothing either, never another statement's.
 */
internal object PathVariableScope {
    private const val FIX_HINT = "Use a statement whose action is a path pattern that captures the variable."

    /**
     * Whether a successful match of [action] always leaves its own captured path variables: a `path` action, or a
     * composite of only such actions.
     */
    fun capturesPathVariables(action: ActionMatcher): Boolean {
        if (action is CompositeActionMatcher) {
            return action.actionMatchers.all { capturesPathVariables(it) }
        }
        return action.type == PathActionMatcherFactory.TYPE
    }

    fun readsPathVariables(condition: ConditionMatcher): Boolean = condition.configuration.readsPathVariables()

    fun readsPathVariables(part: String): Boolean =
        part.startsWith(RequestParts.PATH_VAR_PREFIX) || part.contains(PATH_VARIABLES_KEY)

    fun readsPathVariables(action: ActionMatcher): Boolean =
        action.configuration.anyString { it.contains(RequestParts.PATH_VAR_PREFIX) || it.contains(PATH_VARIABLES_KEY) }

    private fun Configuration.readsPathVariables(): Boolean = anyString { readsPathVariables(it) }

    private fun Configuration.anyString(predicate: (String) -> Boolean): Boolean = when {
        isString -> predicate(asString())
        isArray -> asList().any { it.anyString(predicate) }
        isObject -> asMap().values.any { it.anyString(predicate) }
        else -> false
    }

    fun requireScoped(policy: Policy) {
        requireConditionScoped(policy.condition) { "Policy [${policy.id}] condition" }
        policy.statements.forEachIndexed { index, statement ->
            requireScoped(statement) { "Policy [${policy.id}] statement [$index][${statement.name}]" }
        }
    }

    fun requireScoped(appPermission: AppPermission) {
        requireConditionScoped(appPermission.condition) { "App permission [${appPermission.id}] condition" }
        appPermission.groups.forEach { group ->
            group.permissions.forEach { permission ->
                requireScoped(permission) { "App permission [${appPermission.id}] permission [${permission.id}]" }
            }
        }
    }

    /**
     * Rate limit rules follow statements: only a rule with its own `path` action may read path variables, in its
     * condition or its key.
     */
    fun requireScoped(ruleId: String, action: ActionMatcher, condition: ConditionMatcher, key: String?) {
        requireActionScoped(action) { "Rate limit rule [$ruleId] action" }
        if (capturesPathVariables(action)) {
            return
        }
        if (readsPathVariables(condition)) {
            throw PathVariableScopeException(notCapturedMessage("Rate limit rule [$ruleId] condition", action))
        }
        if (key != null && readsPathVariables(key)) {
            throw PathVariableScopeException(notCapturedMessage("Rate limit rule [$ruleId] key", action))
        }
    }

    private inline fun requireScoped(statement: Statement, where: () -> String) {
        requireActionScoped(statement.action) { "${where()} action" }
        if (!capturesPathVariables(statement.action) && readsPathVariables(statement.condition)) {
            throw PathVariableScopeException(notCapturedMessage("${where()} condition", statement.action))
        }
    }

    private inline fun requireActionScoped(action: ActionMatcher, where: () -> String) {
        if (readsPathVariables(action)) {
            throw PathVariableScopeException(
                "${where()} reads path variables (request.path.var.*), but an action is evaluated before it " +
                    "captures any: a path template cannot use them. Read them in the statement's condition instead.",
            )
        }
    }

    private inline fun requireConditionScoped(condition: ConditionMatcher, where: () -> String) {
        if (readsPathVariables(condition)) {
            throw PathVariableScopeException(
                "${where()} reads path variables (request.path.var.*), but it is evaluated before any action " +
                    "captures them. $FIX_HINT",
            )
        }
    }

    private fun notCapturedMessage(where: String, action: ActionMatcher): String =
        "$where reads path variables (request.path.var.*), but its [${action.type}] action captures none: " +
            "a condition only sees the variables of its own path action. $FIX_HINT"
}
