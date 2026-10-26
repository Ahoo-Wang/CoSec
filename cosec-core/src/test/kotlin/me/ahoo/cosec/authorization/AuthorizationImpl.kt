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

package me.ahoo.cosec.authorization

import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.parity.AuthorizationFactory
import me.ahoo.cosec.parity.FailureView
import me.ahoo.cosec.snapshot.SnapshotUnavailableException

/**
 * The authorization implementations every decision test runs against (RFC 0002 §8.2, RFC 0003 §8.2): the legacy one
 * until RFC 0002 PR-6 removes it, the engine over repositories until RFC 0003 PR-6 removes them, and the engine over a
 * snapshot of what the repositories answer. Use it with `@ParameterizedClass @EnumSource(AuthorizationImpl::class)`.
 */
enum class AuthorizationImpl(private val delegate: AuthorizationFactory) {
    LEGACY(AuthorizationFactory.LEGACY),
    ENGINE(AuthorizationFactory.ENGINE),
    SNAPSHOT(AuthorizationFactory.SNAPSHOT);

    /**
     * What a test expecting [error] sees: the snapshot path fails a layer it cannot read with a
     * [SnapshotUnavailableException] caused by the read's own error (RFC 0003 §7 X4).
     */
    fun failureOf(error: Throwable): Throwable =
        if (this == SNAPSHOT && error is SnapshotUnavailableException) FailureView.INNERMOST_CAUSE.of(error) else error

    fun create(
        policyRepository: PolicyRepository,
        appRolePermissionRepository: AppRolePermissionRepository,
        blacklistChecker: BlacklistChecker = BlacklistChecker.NoOp,
        rootPrincipalMatcher: RootPrincipalMatcher = RootPrincipalMatcher.NONE
    ): DecisionAuthorization =
        delegate.create(policyRepository, appRolePermissionRepository, blacklistChecker, rootPrincipalMatcher)

    /** For [me.ahoo.cosec.shadow.ShadowAuthorization]'s candidate factory. */
    val factory:
        (PolicyRepository, AppRolePermissionRepository, BlacklistChecker, RootPrincipalMatcher) -> DecisionAuthorization
        get() = { policies, permissions, blacklist, root -> create(policies, permissions, blacklist, root) }
}
