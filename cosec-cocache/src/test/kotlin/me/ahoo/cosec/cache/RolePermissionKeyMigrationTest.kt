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

package me.ahoo.cosec.cache

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

/**
 * Migrates 6.x role grants in a real Redis (`localhost:6379`) and checks authorization before and after.
 */
class RolePermissionKeyMigrationTest {
    private lateinit var fixture: RedisRolePermissionFixture
    private lateinit var migration: RolePermissionKeyMigration
    private lateinit var authorization: SimpleAuthorization

    private fun app(id: String) = AppPermissionData(
        id = id,
        groups = listOf(
            PermissionGroupData(
                name = "all",
                permissions = listOf(PermissionData(id = "$id.manage", name = "", action = AllActionMatcher.INSTANCE)),
            ),
        ),
    )

    @BeforeEach
    fun setup() {
        fixture = RedisRolePermissionFixture()
        fixture.appPermissionCache.put("blog", app("blog"))
        fixture.appPermissionCache.put("billing", app("billing"))
        migration = RolePermissionKeyMigration(
            fixture.redisTemplate,
            fixture.rolePermissionCache("migration"),
            fixture.legacyRolePermissionKeyPrefix,
        )
        val policyRepository = mockk<PolicyRepository> {
            every { getGlobalPolicy() } returns Mono.just(emptyList())
        }
        authorization = SimpleAuthorization(policyRepository, fixture.repository(fixture.rolePermissionCache("app")))
    }

    @AfterEach
    fun destroy() {
        fixture.close()
    }

    private fun legacy(suffix: String, vararg permissions: String) {
        fixture.redisTemplate.opsForSet().add(fixture.legacyRolePermissionKeyPrefix + suffix, *permissions)
    }

    private fun authorized(role: String, appId: String, spaceId: String = ""): Boolean {
        val request = EvaluateRequest(
            headers = mapOf(AppIdCapable.APP_ID_KEY to appId, SpaceIdCapable.SPACE_ID_KEY to spaceId)
                .filterValues { it.isNotEmpty() },
        )
        val context = SimpleSecurityContext(SimplePrincipal("alice", roles = setOf(role)))
        return checkNotNull(authorization.decide(request, context).block()).result.authorized
    }

    @Test
    fun legacyGrantsAreNotReadUntilMigratedToTheNamedApp() {
        legacy("editor", AppRolePermission.ALL_PERMISSION_ID)
        legacy("admin@team-a", "blog.manage")

        // Fail closed: 7.0 never reads the legacy keys.
        authorized("editor", "blog").assert().isFalse()
        authorized("admin@team-a", "blog", "team-a").assert().isFalse()

        val report = migration.migrate("blog")

        report.migrated.map { it.target }.assert().containsExactlyInAnyOrder(
            AppSpacedRoleId("blog", "editor"),
            AppSpacedRoleId("blog", "admin", "team-a"),
        )
        // The local cache that saw the miss before the migration is evicted by the migration's write.
        authorized("editor", "blog").assert().isTrue()
        authorized("admin@team-a", "blog", "team-a").assert().isTrue()
        // Only the named app gets the grants, wildcard included.
        authorized("editor", "billing").assert().isFalse()
        authorized("admin@team-a", "billing", "team-a").assert().isFalse()
        fixture.grants(AppSpacedRoleId("blog", "admin", "team-a"))
            .assert().containsExactly("blog.manage")
        // Legacy keys are kept until deleted explicitly.
        fixture.redisTemplate.hasKey(fixture.legacyRolePermissionKeyPrefix + "editor").assert().isTrue()
    }

    @Test
    fun legacyRoleIdContainingColonNeverReadsAsAnotherAppsGrant() {
        // 6.x role "blog:editor" would collide with (blog, editor) if both formats shared a prefix.
        legacy("blog:editor", AppRolePermission.ALL_PERMISSION_ID)
        authorized("editor", "blog").assert().isFalse()

        migration.migrate("billing").migrated.single().target.assert()
            .isEqualTo(AppSpacedRoleId("billing", "blog:editor"))
        authorized("editor", "blog").assert().isFalse()
        authorized("blog:editor", "billing").assert().isTrue()
    }

    @Test
    fun dryRunWritesNothing() {
        legacy("editor", AppRolePermission.ALL_PERMISSION_ID)

        val report = migration.migrate("blog", dryRun = true)

        report.dryRun.assert().isTrue()
        report.migrated.single().target.assert().isEqualTo(AppSpacedRoleId("blog", "editor"))
        fixture.redisTemplate.hasKey("${fixture.rolePermissionKeyPrefix}blog:editor").assert().isFalse()
        authorized("editor", "blog").assert().isFalse()
    }

    @Test
    fun skipsKeysWithoutAMigratableGrant() {
        legacy("ghost", RolePermissionKeyMigration.DEFAULT_MISSING_GUARD_SENTINEL)
        legacy("admin@team-a@x", AppRolePermission.ALL_PERMISSION_ID)
        legacy("@team-a", AppRolePermission.ALL_PERMISSION_ID)
        legacy("admin@", AppRolePermission.ALL_PERMISSION_ID)
        fixture.redisTemplate.opsForValue().set(fixture.legacyRolePermissionKeyPrefix + "string", "*")

        val report = migration.migrate("blog")

        report.migrated.assert().isEmpty()
        report.skipped.map { it.legacyKey.removePrefix(fixture.legacyRolePermissionKeyPrefix) }
            .assert().containsExactlyInAnyOrder("ghost", "admin@team-a@x", "@team-a", "admin@", "string")
    }

    @Test
    fun rerunIsIdempotentAndKeepsConflictsUnlessOverwriting() {
        legacy("editor", "blog.manage")
        migration.migrate("blog").migrated.size.assert().isEqualTo(1)

        val rerun = migration.migrate("blog")
        rerun.migrated.assert().isEmpty()
        rerun.unchanged.single().target.assert().isEqualTo(AppSpacedRoleId("blog", "editor"))

        // A 6.x instance changes the legacy grant during a rolling upgrade.
        legacy("editor", AppRolePermission.ALL_PERMISSION_ID)
        val conflicting = migration.migrate("blog")
        conflicting.conflicts.single().permissions.assert()
            .containsExactlyInAnyOrder("blog.manage", AppRolePermission.ALL_PERMISSION_ID)
        fixture.grants(AppSpacedRoleId("blog", "editor"))
            .assert().containsExactly("blog.manage")

        migration.migrate("blog", overwrite = true).migrated.size.assert().isEqualTo(1)
        fixture.grants(AppSpacedRoleId("blog", "editor"))
            .assert().containsExactlyInAnyOrder("blog.manage", AppRolePermission.ALL_PERMISSION_ID)
    }

    @Test
    fun rolesCanBeSplitBetweenApps() {
        legacy("editor", AppRolePermission.ALL_PERMISSION_ID)
        legacy("accountant", AppRolePermission.ALL_PERMISSION_ID)

        migration.migrate("blog", roleIds = setOf("editor"))
        migration.migrate("billing", roleIds = setOf("accountant"))

        authorized("editor", "blog").assert().isTrue()
        authorized("editor", "billing").assert().isFalse()
        authorized("accountant", "billing").assert().isTrue()
        authorized("accountant", "blog").assert().isFalse()
    }

    @Test
    fun targetAppMustBeNamedAndValid() {
        assertThrownBy<IllegalArgumentException> { migration.migrate("") }
        assertThrownBy<IllegalArgumentException> { migration.migrate("blog:v2") }
    }

    @Test
    fun deleteLegacyRemovesOnlyLegacyKeys() {
        legacy("editor", AppRolePermission.ALL_PERMISSION_ID)
        legacy("admin@team-a", AppRolePermission.ALL_PERMISSION_ID)
        migration.migrate("blog")

        migration.deleteLegacy(dryRun = true).size.assert().isEqualTo(2)
        fixture.redisTemplate.hasKey(fixture.legacyRolePermissionKeyPrefix + "editor").assert().isTrue()

        migration.deleteLegacy().size.assert().isEqualTo(2)
        fixture.redisTemplate.keys(fixture.legacyRolePermissionKeyPrefix + "*").assert().isEmpty()
        authorized("editor", "blog").assert().isTrue()
        authorized("admin@team-a", "blog", "team-a").assert().isTrue()
    }
}
