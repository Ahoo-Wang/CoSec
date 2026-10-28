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
            "cosec-core:PolicyDocumentCodecTest#the wildcard is only ever Any",
            "runtime:OneDecisionPathTest#onlyStatusMappingConstructsOutcomes",
            "runtime:OneDecisionPathTest#adaptersNeverSeeDecisions",
            "runtime:OneDecisionPathTest#bridgesWriteOnlyEncodedProblems",
            "cosec-spring-boot-starter:WebAdapterParityTest#the three adapters answer byte for byte alike",
            "cosec-spring-boot-starter:WebAdapterParityTest#the three adapters make the same decision event",
            "model:ScopeConstructionTest#onlyTheResolverConstructsScopes",
            "model:ScopeConstructionTest#scopeIsNotADataClass",
            "cosec-core:PolicyDocumentCodecTest#a scope is never decoded",
            "cosec-spring-boot-starter:UnverifiedTokenTest#onlyTheVerifierDecodesATokenIntoAPrincipal",
        ),
        "startup" to setOf(
            "cosec-cocache:CacheTtlPolicyTest#snapshotCacheIsStoreOfRecordAndKeepsItsLocalCopiesShort",
            "cosec-cocache:CacheTtlPolicyTest#snapshotCacheKeepsItsPinsWhateverCustomizersSet",
            "cosec-spring-boot-starter:CoSecPipelineAutoConfigurationTest#Redis that may evict snapshots fails startup unless told to warn",
            "cosec-spring-boot-starter:CoSecPipelineAutoConfigurationTest#a maxmemory-policy that cannot be read fails startup unless told to warn",
        ),
        "timing" to setOf(
            "cosec-core:SnapshotConsistencyPropertyTest#everyEvaluationReadsOneRevisionPerDocument",
            "runtime:DocumentSnapshotViewsTest#a view decides with the revisions it was built from",
            "cosec-core:SnapshotInstallOrderPropertyTest#installsFollowLoadOrderAndConvergeInEveryFamily",
            "cosec-core:SnapshotInstallOrderPropertyTest#installsFollowLoadOrderAndConverge",
            "cosec-core:SnapshotRecompileTest#a load that compiled with the old inputs while they changed is compiled again before anyone is served it",
            "cosec-core:SnapshotRecompileTest#a document that cannot be compiled again fails closed until a retry compiles it",
            "cosec-core:SnapshotRecompileTest#a document failed by a recompile keeps retrying while the store stays down",
            "runtime:PlatformCompilerInFlightTest#a document compiled against the previous platform while it changed is compiled again",
        ),
    )
}
