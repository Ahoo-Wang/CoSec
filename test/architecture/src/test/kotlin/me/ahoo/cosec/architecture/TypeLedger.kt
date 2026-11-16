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

/**
 * RFC 0013 §8.1: the main source files that declare more than one non-private top-level type, each a family that stays
 * or a debt that a batch removes. [TypesPerFileTest] fails on a file with several types and no entry, and on an entry
 * whose file is gone or clean again. One mechanism: a sealed family is listed too, with that as its reason.
 *
 * A family is, and nothing else is:
 * - a record and the parts only it is made of ([RECORD]);
 * - a port and the values its own signature carries ([PORT]);
 * - a type and the exception or enum only it uses ([OWN_EXCEPTION]);
 * - a sealed type and its subtypes ([SEALED]).
 *
 * Not a family: a port and an implementation of it (the implementation gets a file of its own); a class and a codec,
 * encoder or helper class that has behaviour; two classes that are wired together. Those are debt, with the batch
 * that splits them. A file of `me.ahoo.cosec.migrate.v7` that mirrors a 7.x source file stays ([MIRROR_7X]).
 */
object TypeLedger {
    const val RECORD = "a record and the parts only it is made of"
    const val PORT = "a port and the values its own signature carries"
    const val OWN_EXCEPTION = "a type and the exception or enum only it uses"
    const val SEALED = "a sealed type and its subtypes"
    val MIRROR_7X = Disposition.Stays("the 7.x model as 7.x had it, file for file")

    private fun family(kind: String, what: String) = Disposition.Stays("$kind: $what")

    val ENTRIES: Map<String, Disposition> = mapOf(
        "api/api/src/main/kotlin/me/ahoo/cosec/api/audit/AuditEvent.kt" to family(RECORD, "the audit record"),
        "api/api/src/main/kotlin/me/ahoo/cosec/api/context/SecurityContext.kt" to family(RECORD, "the security context, its scope and the identity a gateway forwarded"),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/api/AccessReport.kt" to family(RECORD, "an access report"),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/api/ControlPlaneResults.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/api/Simulator.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/audit/ChangeAudit.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/authz/ControlAuthorization.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/lifecycle/ChangeContext.kt" to family(OWN_EXCEPTION, "the change context"),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/grant/GrantFragment.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/grant/GrantModel.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/grant/GrantorRights.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/grant/ScopeSet.kt" to family(RECORD, "a scope set and its dimensions"),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/grant/Specialization.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/store/ControlStore.kt" to family(PORT, "ControlStore and the records it stores"),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/store/Publications.kt" to Disposition.Debt(Batch.B4),
        "core/document/src/main/kotlin/me/ahoo/cosec/document/Catalog.kt" to family(RECORD, "a catalog, its groups and permissions"),
        "core/document/src/main/kotlin/me/ahoo/cosec/document/Provenance.kt" to family(RECORD, "a provenance"),
        "core/document/src/main/kotlin/me/ahoo/cosec/document/RoleDefinition.kt" to family(RECORD, "a role and what it grants"),
        "core/document/src/main/kotlin/me/ahoo/cosec/document/format/FormatType.kt" to family(SEALED, "the format types, with the object type's field"),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/CompiledDocument.kt" to family(RECORD, "a compiled document and its bound entries"),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/ExternalValues.kt" to family(OWN_EXCEPTION, "the external values"),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/Engine.kt" to family(PORT, "the engine port with the request it takes and the evaluation it returns"),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/pattern/HttpPathPattern.kt" to family(RECORD, "a path pattern, its elements and its captures"),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/pattern/ResourcePattern.kt" to family(RECORD, "a resource pattern, its segments and its target"),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/trace/EvaluationTrace.kt" to family(RECORD, "an evaluation trace"),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/AuthzRequest.kt" to family(RECORD, "an authorization request"),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/Binding.kt" to family(RECORD, "a binding"),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/Ids.kt" to family(RECORD, "the ids and the syntax they share"),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/condition/Attribute.kt" to family(PORT, "AttributeCatalog and the attributes it answers"),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/condition/Condition.kt" to family(SEALED, "the condition AST"),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/condition/ConditionContainment.kt" to family(OWN_EXCEPTION, "the containment check"),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/condition/Time.kt" to family(PORT, "ZoneResolver and the offsets of a zone it answers"),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/decision/Decision.kt" to family(SEALED, "a decision, its causes and obligations"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/http/Outcome.kt" to family(RECORD, "an outcome, its basis and why it is unavailable"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/attribute/BlacklistSource.kt" to family(PORT, "BlacklistSource and the kinds it is asked about"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/audit/AuditEventData.kt" to family(RECORD, "the audit record's implementation"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/event/DecisionEvent.kt" to family(RECORD, "a decision event and its summaries"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/event/DecisionListener.kt" to family(PORT, "DecisionListener and its delivery"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/http/HttpAttributes.kt" to family(OWN_EXCEPTION, "the request attributes"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/http/HttpFacts.kt" to family(RECORD, "a request's facts, its headers and cookies"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/shadow/Candidates.kt" to family(PORT, "CandidateSource and the candidate it answers"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/snapshot/SnapshotMetrics.kt" to family(PORT, "the snapshot metrics and the load outcomes they count"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/snapshot/SnapshotViews.kt" to family(PORT, "the snapshot views and the key a view is asked by"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/apikey/ApiKey.kt" to family(PORT, "ApiKeyReader and the key it reads"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/attribute/AttributeProvider.kt" to family(PORT, "AttributeProvider"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/authn/Authenticator.kt" to family(PORT, "Authenticator"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/identity/Identity.kt" to family(OWN_EXCEPTION, "the forwarded identity"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/shadow/InstanceStatus.kt" to family(RECORD, "an instance status and its documents"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/shadow/ShadowReport.kt" to family(RECORD, "a shadow report, its samples and outcomes"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/snapshot/SnapshotDocument.kt" to family(RECORD, "a stored document and its digest"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/snapshot/SnapshotDocumentStore.kt" to family(PORT, "SnapshotDocumentStore"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/snapshot/SnapshotKey.kt" to family(OWN_EXCEPTION, "the snapshot key"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/token/RevocationReader.kt" to family(PORT, "RevocationReader and the subject revocation it reads"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/token/TokenFamilyStore.kt" to family(PORT, "TokenFamilyStore and the session family it keeps"),
        "edge/openapi/src/main/kotlin/me/ahoo/cosec/openapi/generator/OpenAPIServiceGenerator.kt" to family(OWN_EXCEPTION, "the OpenAPI generator"),
        "extensions/ip2region/src/main/kotlin/me/ahoo/cosec/ip2region/Ip2RegionAttributeProvider.kt" to family(OWN_EXCEPTION, "the region provider"),
        "host/spring-boot/src/main/kotlin/me/ahoo/cosec/spring/boot/CoSecModuleCheckAutoConfiguration.kt" to family(OWN_EXCEPTION, "the module check and the Spring condition only it uses"),
        "host/spring-boot/src/main/kotlin/me/ahoo/cosec/spring/boot/jwt/ConditionalOnJwtEnabled.kt" to family(OWN_EXCEPTION, "the annotation and the Spring condition only it uses"),
        "host/spring-boot/src/main/kotlin/me/ahoo/cosec/spring/boot/pipeline/ClientIp.kt" to family(OWN_EXCEPTION, "the client IP readings and the configuration exception only they throw"),
        "host/spring-boot/src/main/kotlin/me/ahoo/cosec/spring/boot/pipeline/PipelineProperties.kt" to family(RECORD, "one cosec.* property tree, bound by Spring"),
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/Bootstrap.kt" to family(OWN_EXCEPTION, "the bootstrap"),
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/ConversionReport.kt" to family(RECORD, "a conversion report"),
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/Converter.kt" to Disposition.Stays("migrate's one-off conversion of 7.x data, run once per deployment"),
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/PartTranslation.kt" to Disposition.Stays("migrate's one-off translation of 7.x condition parts"),
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/api/authorization/AuthorizeResult.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/api/policy/PermissionVerifier.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/api/principal/RoleCapable.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/Expression.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/PathVariableScope.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/action/AllActionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/action/CompositeActionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/action/PathActionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/AllConditionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/BoolConditionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/OgnlConditionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/OgnlSandbox.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/SpelConditionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/TimeConditionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/context/AuthenticatedConditionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/context/InRoleConditionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/context/InTenantConditionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/part/BoundedRegex.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/part/ContainsConditionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/part/EndsWithConditionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/part/EqConditionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/part/InConditionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/part/PartExtractor.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/part/PathConditionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/part/RegularConditionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/policy/condition/part/StartsWithConditionMatcher.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/serialization/AbstractJsonStatementSerializer.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/serialization/JsonActionMatcherSerializer.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/serialization/JsonAppPermissionSerializer.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/serialization/JsonConditionMatcherSerializer.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/serialization/JsonConfigurationSerializer.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/serialization/JsonEffectSerializer.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/serialization/JsonPermissionGroupSerializer.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/serialization/JsonPermissionSerializer.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/serialization/JsonPolicySerializer.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/serialization/JsonPolicyTypeSerializer.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/serialization/JsonRateLimitRuleSerializer.kt" to MIRROR_7X,
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/v7/serialization/JsonStatementSerializer.kt" to MIRROR_7X,
        "test/testkit/src/main/kotlin/me/ahoo/cosec/testkit/SnapshotCompiler.kt" to family(PORT, "SnapshotCompiler and the documents it takes and the snapshot it gives"),
        "test/testkit/src/main/kotlin/me/ahoo/cosec/testkit/ScenarioJson.kt" to family(OWN_EXCEPTION, "the scenario reader"),
    )
}
