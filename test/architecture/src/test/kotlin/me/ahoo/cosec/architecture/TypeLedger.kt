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
        "backends/cocache/src/main/kotlin/me/ahoo/cosec/cocache/CoCacheMembershipProvider.kt" to Disposition.Debt(Batch.B7),
        "backends/cocache/src/main/kotlin/me/ahoo/cosec/cocache/CoCacheTokenFamilyStore.kt" to Disposition.Debt(Batch.B7),
        "backends/cocache/src/main/kotlin/me/ahoo/cosec/cocache/MembershipCache.kt" to Disposition.Debt(Batch.B7),
        "backends/cocache/src/main/kotlin/me/ahoo/cosec/cocache/control/ControlCacheSpecs.kt" to Disposition.Debt(Batch.B7),
        "backends/cocache/src/main/kotlin/me/ahoo/cosec/cocache/shadow/CoCacheShadow.kt" to Disposition.Debt(Batch.B7),
        "backends/cocache/src/main/kotlin/me/ahoo/cosec/cocache/snapshot/PolicySnapshotCache.kt" to Disposition.Debt(Batch.B7),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/api/AccessReport.kt" to family(RECORD, "an access report"),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/api/ControlHttp.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/api/ControlPlaneResults.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/api/Simulator.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/audit/ChangeAudit.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/authz/ControlAuthorization.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/change/Candidates.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/change/ChangeAuthority.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/change/ChangeChecks.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/change/ChangeContext.kt" to family(OWN_EXCEPTION, "the change context"),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/change/ChangeModel.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/change/Changes.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/change/Confirmations.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/change/MovedHeads.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/change/Narrowing.kt" to Disposition.Debt(Batch.B4),
        "core/control/src/main/kotlin/me/ahoo/cosec/control/change/ShadowWindows.kt" to Disposition.Debt(Batch.B4),
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
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/flat/AttributePlan.kt" to Disposition.Debt(Batch.B3),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/flat/CandidateOrder.kt" to Disposition.Debt(Batch.B3),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/flat/CompiledDocument.kt" to family(RECORD, "a compiled document and its bound entries"),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/flat/CompiledPolicy.kt" to Disposition.Debt(Batch.B3),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/flat/CompiledStatement.kt" to Disposition.Debt(Batch.B3),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/flat/ExternalValues.kt" to family(OWN_EXCEPTION, "the external values"),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/flat/FlatEngine.kt" to Disposition.Debt(Batch.B3),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/flat/PolicyCompiler.kt" to Disposition.Debt(Batch.B3),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/flat/RateLimits.kt" to Disposition.Debt(Batch.B3),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/flat/StatementIndex.kt" to Disposition.Debt(Batch.B3),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/pattern/EvaluationScratch.kt" to Disposition.Debt(Batch.B3),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/pattern/HttpPath.kt" to Disposition.Debt(Batch.B3),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/pattern/HttpPathPattern.kt" to family(RECORD, "a path pattern, its elements and its captures"),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/pattern/HttpPathPatternParser.kt" to Disposition.Debt(Batch.B3),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/pattern/PatternContainment.kt" to Disposition.Debt(Batch.B3),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/pattern/ResourcePattern.kt" to family(RECORD, "a resource pattern, its segments and its target"),
        "core/engine/src/main/kotlin/me/ahoo/cosec/engine/trace/EvaluationTrace.kt" to family(RECORD, "an evaluation trace"),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/AuthzRequest.kt" to family(RECORD, "an authorization request"),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/Binding.kt" to family(RECORD, "a binding"),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/Ids.kt" to family(RECORD, "the ids and the syntax they share"),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/condition/Attribute.kt" to family(PORT, "AttributeCatalog and the attributes it answers"),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/condition/Cidr.kt" to Disposition.Debt(Batch.B7),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/condition/Condition.kt" to family(SEALED, "the condition AST"),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/condition/ConditionChecker.kt" to Disposition.Debt(Batch.B7),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/condition/ConditionContainment.kt" to family(OWN_EXCEPTION, "the containment check"),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/condition/Time.kt" to Disposition.Debt(Batch.B7),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/decision/Decision.kt" to family(SEALED, "a decision, its causes and obligations"),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/decision/StatementRef.kt" to Disposition.Debt(Batch.B7),
        "core/model/src/main/kotlin/me/ahoo/cosec/model/scope/ScopeResolver.kt" to Disposition.Debt(Batch.B7),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/http/Outcome.kt" to family(RECORD, "an outcome, its basis and why it is unavailable"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/attribute/BlacklistSource.kt" to family(PORT, "BlacklistSource and the kinds it is asked about"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/audit/AuditEventData.kt" to family(RECORD, "the audit record's implementation"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/event/DecisionEvent.kt" to family(RECORD, "a decision event and its summaries"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/event/DecisionListener.kt" to family(PORT, "DecisionListener and its delivery"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/http/HttpAttributes.kt" to family(OWN_EXCEPTION, "the request attributes"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/http/HttpFacts.kt" to family(RECORD, "a request's facts, its headers and cookies"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/shadow/Candidates.kt" to family(PORT, "CandidateSource and the candidate it answers"),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/snapshot/DocumentSnapshotViews.kt" to Disposition.Debt(Batch.B2a),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/snapshot/Snapshot.kt" to Disposition.Debt(Batch.B2a),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/snapshot/SnapshotMetrics.kt" to Disposition.Debt(Batch.B2a),
        "core/runtime/src/main/kotlin/me/ahoo/cosec/runtime/snapshot/SuspendingSnapshotSource.kt" to Disposition.Debt(Batch.B2a),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/apikey/ApiKeys.kt" to Disposition.Debt(Batch.B7),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/attribute/AttributeProvider.kt" to family(PORT, "AttributeProvider"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/authn/Authenticator.kt" to family(PORT, "Authenticator"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/identity/Identity.kt" to family(OWN_EXCEPTION, "the forwarded identity"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/ratelimit/RateLimit.kt" to Disposition.Debt(Batch.B7),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/shadow/InstanceStatus.kt" to family(RECORD, "an instance status and its documents"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/shadow/ShadowReport.kt" to family(RECORD, "a shadow report, its samples and outcomes"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/snapshot/SnapshotDocument.kt" to family(RECORD, "a stored document and its digest"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/snapshot/SnapshotDocumentStore.kt" to family(PORT, "SnapshotDocumentStore"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/snapshot/SnapshotExceptions.kt" to Disposition.Debt(Batch.B7),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/snapshot/SnapshotKey.kt" to family(OWN_EXCEPTION, "the snapshot key"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/token/RevocationReader.kt" to family(PORT, "RevocationReader and the subject revocation it reads"),
        "core/spi/src/main/kotlin/me/ahoo/cosec/spi/token/TokenFamilyStore.kt" to Disposition.Debt(Batch.B7),
        "edge/gateway/src/main/kotlin/me/ahoo/cosec/gateway/IdentityGuard.kt" to Disposition.Debt(Batch.B7),
        "edge/openapi/src/main/kotlin/me/ahoo/cosec/openapi/generator/OpenAPIServiceGenerator.kt" to family(OWN_EXCEPTION, "the OpenAPI generator"),
        "edge/webflux/src/main/kotlin/me/ahoo/cosec/webflux/PipelineWebFilter.kt" to Disposition.Debt(Batch.B7),
        "edge/webmvc/src/main/kotlin/me/ahoo/cosec/webmvc/PipelineServletFilter.kt" to Disposition.Debt(Batch.B7),
        "extensions/ip2region/src/main/kotlin/me/ahoo/cosec/ip2region/Ip2RegionAttributeProvider.kt" to family(OWN_EXCEPTION, "the region provider"),
        "extensions/login/src/main/kotlin/me/ahoo/cosec/login/token/RefreshTokenAuthentication.kt" to Disposition.Debt(Batch.B7),
        "extensions/opentelemetry/src/main/kotlin/me/ahoo/cosec/opentelemetry/TracingListener.kt" to Disposition.Debt(Batch.B7),
        "extensions/social/src/main/kotlin/me/ahoo/cosec/social/SocialStateStore.kt" to Disposition.Debt(Batch.B7),
        "host/spring-boot/src/main/kotlin/me/ahoo/cosec/spring/boot/CoSecModuleCheckAutoConfiguration.kt" to family(OWN_EXCEPTION, "the module check and the Spring condition only it uses"),
        "host/spring-boot/src/main/kotlin/me/ahoo/cosec/spring/boot/CoSecProperties.kt" to Disposition.Debt(Batch.B7),
        "host/spring-boot/src/main/kotlin/me/ahoo/cosec/spring/boot/StartupTask.kt" to Disposition.Debt(Batch.B7),
        "host/spring-boot/src/main/kotlin/me/ahoo/cosec/spring/boot/actuate/CoSecServiceGeneratorEndpoint.kt" to Disposition.Debt(Batch.B7),
        "host/spring-boot/src/main/kotlin/me/ahoo/cosec/spring/boot/control/CoSecControlAutoConfiguration.kt" to Disposition.Debt(Batch.B7),
        "host/spring-boot/src/main/kotlin/me/ahoo/cosec/spring/boot/control/CoSecControlRoutesAutoConfiguration.kt" to Disposition.Debt(Batch.B7),
        "host/spring-boot/src/main/kotlin/me/ahoo/cosec/spring/boot/jwt/ConditionalOnJwtEnabled.kt" to family(OWN_EXCEPTION, "the annotation and the Spring condition only it uses"),
        "host/spring-boot/src/main/kotlin/me/ahoo/cosec/spring/boot/pipeline/ClientIp.kt" to Disposition.Debt(Batch.B7),
        "host/spring-boot/src/main/kotlin/me/ahoo/cosec/spring/boot/pipeline/CoSecPipelineAutoConfiguration.kt" to Disposition.Debt(Batch.B7),
        "host/spring-boot/src/main/kotlin/me/ahoo/cosec/spring/boot/pipeline/EvictionPolicyCheck.kt" to Disposition.Debt(Batch.B7),
        "host/spring-boot/src/main/kotlin/me/ahoo/cosec/spring/boot/pipeline/PipelineProperties.kt" to family(RECORD, "one cosec.* property tree, bound by Spring"),
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/ApiKeyMigration.kt" to Disposition.Debt(Batch.B6),
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/Bootstrap.kt" to family(OWN_EXCEPTION, "the bootstrap"),
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/ConditionTranslator.kt" to Disposition.Debt(Batch.B6),
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/ConversionReport.kt" to family(RECORD, "a conversion report"),
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/Converter.kt" to Disposition.Debt(Batch.B6),
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/DocumentPublisher.kt" to Disposition.Debt(Batch.B6),
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/PartTranslation.kt" to Disposition.Debt(Batch.B6),
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/RateLimitTranslation.kt" to Disposition.Debt(Batch.B6),
        "migration/migrate/src/main/kotlin/me/ahoo/cosec/migrate/ReferenceRepair.kt" to Disposition.Debt(Batch.B6),
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
        "test/testkit/src/main/kotlin/me/ahoo/cosec/testkit/DocumentSnapshotCompiler.kt" to Disposition.Debt(Batch.B7),
        "test/testkit/src/main/kotlin/me/ahoo/cosec/testkit/ScenarioJson.kt" to family(OWN_EXCEPTION, "the scenario reader"),
    )
}
