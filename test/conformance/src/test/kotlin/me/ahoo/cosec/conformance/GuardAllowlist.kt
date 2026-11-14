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

package me.ahoo.cosec.conformance

/**
 * The only tests that may cover a `build`, `startup` or `timing` rule ([RuleCoverage.GUARDED_LEVELS]), by level.
 *
 * A scenario rule is covered by scenarios the conformance runner drives against the real engine and pipeline; a
 * guarded rule is covered by whatever test its registry entry names, which no runner checks does what the rule says.
 * So naming one is a reviewed change here, in code, and not only a line in a rule family file: a test listed for a
 * rule must also be listed under the rule's level below, and an entry no rule of its level names fails as stale.
 */
object GuardAllowlist {
    val GUARDS: Map<String, Set<String>> = mapOf(
        "build" to setOf(
            "runtime:PolicyDocumentCodecTest#the wildcard is only ever Any",
            "architecture:OneDecisionPathTest#onlyStatusMappingConstructsOutcomes",
            "architecture:OneDecisionPathTest#adaptersNeverSeeDecisions",
            "architecture:OneDecisionPathTest#bridgesWriteOnlyEncodedProblems",
            "spring-boot:WebAdapterParityTest#the three adapters answer byte for byte alike",
            "spring-boot:WebAdapterParityTest#the three adapters make the same decision event",
            "architecture:ScopeConstructionTest#onlyTheResolverConstructsScopes",
            "architecture:ScopeConstructionTest#scopeIsNotADataClass",
            "runtime:PolicyDocumentCodecTest#a scope is never decoded",
            "architecture:UnverifiedTokenTest#onlyTheVerifierDecodesATokenIntoAPrincipal",
            "architecture:UnverifiedTokenTest#withinTheVerifierOnlyTheVerifyingMethodsDecode",
            "architecture:ObligationDispatchTest#obligations are dispatched by an exhaustive when without else",
            "architecture:ObligationDispatchTest#no runtime code skips a kind of obligation",
            "runtime:PipelineTest#anUnhandledKindUnderVersionSkewIs500",
            "spring-boot:WebAdapterParityTest#the three adapters hand on no client identity",
            "webflux:PipelineWebFilterTest#aPermitHandsOnNoClientIdentity",
            "webmvc:PipelineServletFilterTest#aPermitHandsOnNoClientIdentity",
            "runtime:IdentityForwardingTest#without an issuer a permit strips the client's identity and forwards none",
            "cocache:StoredFormatTest#a duplicate key fails every stored format",
            "control:ControlHttpTest#a body with a duplicate key is refused",
            "control:ControlHttpTest#a field that does not parse is a 400 that names it, never an exception's message",
            "control:ControlHttpTest#a stored document that does not decode is a 500 without detail, logged with its key",
            "cocache:ReactiveRedisRateLimiterTest#aBucketsTwoWindowsShareASlotWhateverThePrefixRuleAndBucketHold",
            "cocache:ReactiveRedisRateLimiterTest#aPrefixWithAnEmptyHashTagIsRefused",
            "cocache:ReactiveRedisRateLimiterTest#aRuleIdThatWouldEmptyTheHashTagIsRefused",
            "cocache:ReactiveRedisRateLimiterTest#bucketsSpreadOverTheCluster",
        ),
        "startup" to setOf(
            "spring-boot:StarterWiringTest#a required Kafka audit that cannot be created fails startup",
            "spring-boot:CoSecModuleCheckAutoConfigurationTest#cosec-cocache without a Redis template fails startup",
            "spring-boot:CoSecModuleCheckAutoConfigurationTest#cosec-cocache without a CacheRuntime fails startup naming the CoCache starter",
            "spring-boot:CoSecModuleCheckAutoConfigurationTest#cosec-cocache is still needed where it is used",
            "spring-boot:CoSecModuleCheckAutoConfigurationTest#a service in gateway mode does not need Redis for an unused cosec-cocache",
            "spring-boot:CoSecModuleCheckAutoConfigurationTest#a Spring MVC host without cosec-webmvc fails startup",
            "spring-boot:CoSecModuleCheckAutoConfigurationTest#a WebFlux host without cosec-webflux fails startup",
            "spring-boot:CoSecModuleCheckAutoConfigurationTest#a Spring Cloud Gateway host without cosec-gateway fails startup, even with CoSec off",
            "spring-boot:CoSecModuleCheckAutoConfigurationTest#Redis API keys without cosec-cocache fail startup",
            "spring-boot:CoSecModuleCheckAutoConfigurationTest#a required Kafka audit without cosec-kafka fails startup",
            "spring-boot:CoSecModuleCheckAutoConfigurationTest#a CosId generator for CoSec's ids without cosec-cosid fails startup",
            "spring-boot:CoSecApiKeyAutoConfigurationTest#apiKeysNeedAMembershipProvider",
            "cocache:CacheTtlPolicyTest#snapshotCacheIsStoreOfRecordAndKeepsItsLocalCopiesShort",
            "cocache:CacheTtlPolicyTest#storeOfRecordCachesNeverExpireAndFailClosed",
            "spring-boot:StoreOfRecordOverrideTest#breakingTheGlassStillLeavesAStoreOfRecord",
            "spring-boot:CoSecPipelineAutoConfigurationTest#Redis that may evict snapshots fails startup unless told to warn",
            "spring-boot:CoSecPipelineAutoConfigurationTest#a maxmemory-policy that cannot be read fails startup unless told to warn",
            "spring-boot:CoSecGatewayEndpointCheckAutoConfigurationTest#the gateway endpoint on the application port fails startup",
            "spring-boot:CoSecGatewayEndpointCheckAutoConfigurationTest#controller endpoints are checked as every other endpoint is",
            "spring-boot:CoSecGatewayEndpointCheckAutoConfigurationTest#endpoints on a separate management port start",
            "spring-boot:CoSecGatewayEndpointCheckAutoConfigurationTest#a listed endpoint starts, but gateway is never accepted",
            "spring-boot:CoSecGatewayEndpointCheckAutoConfigurationTest#health alone starts",
            "cocache:CacheTtlPolicyTest#aSnapshotCacheBuiltOutsideTheStarterIsPinned",
            "spring-boot:StoreOfRecordOverrideTest#customizersCannotReplaceWhatDecidesACachesContent",
            "spring-boot:SnapshotEvictionHealthIndicatorTest#the checks read the snapshot runtime's backend, not another backend bean",
            "spring-boot:CoSecPipelineAutoConfigurationTest#a revalidate-interval that turns off or unbinds the periodic pass fails startup",
            "runtime:SnapshotRevalidationTest#theRevalidationIntervalCannotTurnThePassOffOrUnbindIt",
            "runtime:SnapshotRevalidationTest#f9NotificationBeforeTheChangeIsReadableConvergesOnPeriodicRevalidation",
            "runtime:SnapshotRevalidationTest#f10ResetReloadsEveryInstalledDocument",
            "runtime:SnapshotRevalidationTest#revalidationAsksTheStoreNeverALocalCopy",
            "runtime:SnapshotRevalidationTest#aPeriodicPassNeverOverlapsAnotherAndTheOneItOwesFollowsIt",
            "runtime:SnapshotRevalidationTest#aPassHeldOnlyByACompilationDoesNotHoldBackTheNext",
            "runtime:SnapshotRevalidationTest#aPassThatKeepsFinishingDocumentsIsNotAbandoned",
            "runtime:SnapshotRevalidationTest#aPassWithAReadInFlightIsNeverAbandoned",
            "runtime:SnapshotRevalidationTest#aPassDoesNotWaitForACompilationThatOutlivedTheStoreReadTimeout",
            "spring-boot:DeploymentKeyPrefixTest#every key family the starter wires lives under cosec key-prefix",
            "spring-boot:UnknownPropertiesTest#a key prefix cosec key-prefix replaced fails startup",
            "spring-boot:UnknownPropertiesTest#a replaced key prefix in the environment fails startup",
            "spring-boot:UnknownPropertiesTest#no property turns the check off",
        ),
        "timing" to setOf(
            "runtime:SnapshotConsistencyPropertyTest#everyEvaluationReadsOneRevisionPerDocument",
            "runtime:DocumentSnapshotViewsTest#a view decides with the revisions it was built from",
            "runtime:SnapshotInstallOrderPropertyTest#installsFollowLoadOrderAndConvergeInEveryFamily",
            "runtime:SnapshotInstallOrderPropertyTest#installsFollowLoadOrderAndConverge",
            "runtime:SnapshotRecompileTest#a load that compiled with the old inputs while they changed is compiled again before anyone is served it",
            "runtime:SnapshotRecompileTest#a document that cannot be compiled again fails closed until a retry compiles it",
            "runtime:SnapshotRecompileTest#a document failed by a recompile keeps retrying while the store stays down",
            "runtime:PlatformCompilerInFlightTest#a document compiled against the previous platform while it changed is compiled again",
            "runtime:SnapshotFailClosedPropertyTest#absentEntriesNeverEvictDocuments",
            "runtime:DocumentSnapshotSourceTest#a flood of ids without a document never evicts a document",
            "runtime:SnapshotFailClosedPropertyTest#aFailedCompilationNeverKeepsAnOlderRevision",
            "runtime:SnapshotSourceFailureTest#f5NewRevisionWhoseCompilationOverflowsTheStackReplacesTheInstalledOne",
            "runtime:SnapshotReviewFixesTest#virtualMachineErrorInACompilationFailsTheLoadWithoutWedgingTheKey",
        ),
    )
}
