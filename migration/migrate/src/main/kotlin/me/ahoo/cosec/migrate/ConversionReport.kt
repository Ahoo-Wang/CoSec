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

package me.ahoo.cosec.migrate

/**
 * Why `convert` refuses some 7.x data (RFC 0004 §8.4, RFC 0008 §4.1): data 7.x fails on or whose meaning is uncertain,
 * and what this version cannot translate yet. The converter never decides for the operator: every rejection names its
 * location, and nothing is written until the data is fixed and `convert` runs again.
 */
enum class RejectionKind(val advice: String) {
    /** The Redis data is not in the 7.0 layout. */
    SCHEMA_MARKER("migrate 6.x data to 7.0 first (CoSecCoCache5Migration), then convert"),

    /** The global policy index lists a policy that has no content: 7.x fails every request. */
    GLOBAL_INDEX_WITHOUT_CONTENT("store the policy, or remove its id from the global policy index"),

    /** A policy of type `GLOBAL` outside the global index: forgotten in the index, or the wrong type? */
    GLOBAL_TYPE_NOT_INDEXED("add the policy to the global policy index, or change its type"),

    /** A grant names a permission its app does not define: 7.x fails the request that reaches it (C6). */
    UNDEFINED_PERMISSION("define the permission in the app permission, or remove it from the grant"),

    /** A policy or app permission that is not valid 7.x JSON, or names a matcher type no factory is registered for. */
    UNDECODABLE("fix the stored JSON, or put the jar of the custom matcher factory on the classpath"),

    /** A policy id starting with a prefix generated ids use (`perm:`, `g:`, `r:`, `cosec:`). */
    RESERVED_POLICY_ID("rename the policy and the attachments that name it"),

    /** An id 8.0 cannot hold: `*` or empty as an app or space id, `:` in an app id, `@` in a role id. */
    INVALID_ID("rename it; `*` matches any app or space in 8.0, so no app or space may be called `*`"),

    /** The same id twice: a policy in an export, or a permission in one app. */
    DUPLICATE_ID("keep one of them"),

    /** `--partition by-owner` and a policy whose `tenantId` names no tenant document. */
    OWNER_TENANT("set the policy's tenantId, or convert with --partition platform"),

    /** A built-in 7.x condition other than `all`: translated into the condition AST by RFC 0005 PR-6. */
    CONDITION_NOT_TRANSLATED("not translated yet: RFC 0005 PR-6 adds the condition translation"),

    /** `spel` and `ognl`: no translation exists (RFC 0005 §7.1). */
    EXPRESSION_CONDITION("rewrite the expression as conditions of the 8.0 policy language"),

    /** A condition of a custom `ConditionMatcherFactory` (RFC 0008 Q4). */
    CUSTOM_CONDITION("move the attribute it reads into an AttributeProvider (RFC 0006) and write a condition on it"),

    /** A 7.x action this version does not translate: a path template, a `separator` other than `/`, mixed methods. */
    ACTION_NOT_TRANSLATED("see the message; path templates are translated by RFC 0005 PR-6"),

    /** An action of a custom `ActionMatcherFactory` (RFC 0005 §7.2). */
    CUSTOM_ACTION("rewrite it as http paths and methods"),

    /** A translated statement does not compile, e.g. a path pattern outside the `HttpPathPattern` subset. */
    DOES_NOT_COMPILE("see the message; RFC 0005 §5.2 lists the path patterns 8.0 accepts"),
}

/** One refused piece of 7.x data, at [location]: a Redis key or an export file, then a JSON path. */
data class Rejection(val kind: RejectionKind, val location: String, val message: String) {
    val advice: String get() = kind.advice
}

/** 7.x data that is dropped because it never took effect, or kept with a caveat. */
data class Warning(val location: String, val message: String)

/**
 * A 7.x configuration shape whose decisions differ in 8.0 by design (RFC 0004 §5.3 C1–C6, RFC 0005 §8 D1–D3), at
 * [location]. `upgrading.md` explains each [change]; the report only says where the data has the shape.
 */
data class SemanticChange(val change: String, val location: String, val shape: String)

/** What `convert` found: written to `report.json` and summarized on the console. */
class ConversionReport(
    val rejections: List<Rejection>,
    val warnings: List<Warning>,
    val semanticChanges: List<SemanticChange>,
    /** Counts of what was read and written, by name. */
    val counts: Map<String, Int>,
    val inputSha256: String,
) {
    val accepted: Boolean get() = rejections.isEmpty()
}
