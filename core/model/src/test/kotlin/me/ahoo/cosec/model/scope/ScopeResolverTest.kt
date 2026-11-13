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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.model.scope

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ScopeResolverTest {
    private val acme = TenantId("acme")
    private val memberships = Memberships(
        Memberships.Source.CLAIMS,
        linkedMapOf(
            SpaceId.DEFAULT to linkedSetOf(RoleId("viewer"), RoleId("admin")),
            SpaceId("team-a") to linkedSetOf(RoleId("editor")),
            SpaceId("team-b") to emptySet(),
        ),
        emptyList(),
    )
    private fun credential(app: String?) = ScopeSubject(acme, authenticated = true, memberships, app)

    private val user = credential("order")
    private val noHost = HostScope(null)

    private fun resolved(resolution: ScopeResolution): Scope {
        resolution.assert().isInstanceOf(ScopeResolution.Resolved::class.java)
        return (resolution as ScopeResolution.Resolved).scope
    }

    private fun rejectedFor(resolution: ScopeResolution): ScopeResolution.Rejected.Reason {
        resolution.assert().isInstanceOf(ScopeResolution.Rejected::class.java)
        return (resolution as ScopeResolution.Rejected).reason
    }

    @Test
    fun `without a declared space the default space and its roles apply`() {
        val scope = resolved(ScopeResolver.resolve(user, "", noHost))
        scope.tenant.assert().isEqualTo(acme)
        scope.app.assert().isEqualTo(AppId("order"))
        scope.space.assert().isEqualTo(SpaceId.DEFAULT)
        scope.roles.assert().containsExactly(RoleId("viewer"), RoleId("admin"))
    }

    @Test
    fun `a declared space the principal has a role in applies with those roles only`() {
        val scope = resolved(ScopeResolver.resolve(user, "team-a", noHost))
        scope.space.assert().isEqualTo(SpaceId("team-a"))
        scope.roles.assert().containsExactly(RoleId("editor"))
    }

    @Test
    fun `a declared space the principal has no role in is rejected`() {
        rejectedFor(ScopeResolver.resolve(user, "team-c", noHost))
            .assert().isEqualTo(ScopeResolution.Rejected.Reason.SPACE_NOT_MEMBER)
        rejectedFor(ScopeResolver.resolve(user, "team-b", noHost))
            .assert().isEqualTo(ScopeResolution.Rejected.Reason.SPACE_NOT_MEMBER)
    }

    /** RC1-37: spaces beginning with `(` are CoSec's, so no principal is a member of one, whatever it claims. */
    @Test
    fun `a reserved space is rejected even for a principal that claims roles in it`() {
        val claiming = ScopeSubject(
            acme,
            authenticated = true,
            Memberships(Memberships.Source.CLAIMS, mapOf(SpaceId("(default)") to setOf(RoleId("admin"))), emptyList()),
        )
        for (space in listOf("(default)", "(x", "(")) {
            rejectedFor(ScopeResolver.resolve(claiming, space, noHost))
                .assert().describedAs(space).isEqualTo(ScopeResolution.Rejected.Reason.SPACE_NOT_MEMBER)
        }
        ScopeResolver.isReservedSpaceId("x(").assert().isFalse()
        ScopeResolver.isReservedSpaceId("-").assert().isFalse()
    }

    @Test
    fun `without any role the default space still applies`() {
        val nobody = ScopeSubject(acme, true, Memberships(Memberships.Source.PROVIDER, emptyMap(), emptyList()))
        val scope = resolved(ScopeResolver.resolve(nobody, "", noHost))
        scope.app.assert().isEqualTo(AppId(""))
        scope.space.assert().isEqualTo(SpaceId.DEFAULT)
        scope.roles.assert().isEmpty()
    }

    /** Spaces match exactly: no trimming, no case folding, and the wildcard is never a member. */
    @Test
    fun `a near miss of a member space is rejected`() {
        for (space in listOf(" team-a", "team-a ", "TEAM-A", "*", "a@b", "team-a@team-a")) {
            rejectedFor(ScopeResolver.resolve(user, space, noHost))
                .assert().describedAs(space).isEqualTo(ScopeResolution.Rejected.Reason.SPACE_NOT_MEMBER)
        }
    }

    /**
     * Unicode near misses: invisible or look-alike characters and other normal forms. A resolver that normalised (NFC,
     * NFKC, `strip`) would accept some of them while the backend reads the header as sent.
     */
    @Test
    fun `a unicode near miss of a member space or the host app is rejected`() {
        val nfc = "café"
        val subject = ScopeSubject(
            acme,
            authenticated = true,
            Memberships(Memberships.Source.CLAIMS, mapOf(SpaceId(nfc) to setOf(RoleId("editor"))), emptyList()),
        )
        resolved(ScopeResolver.resolve(subject, nfc, noHost)).space.value.assert()
            .isEqualTo(nfc)
        val spaces = listOf(
            "café", // NFD of the member space
            "café​", // zero-width space
            "﻿café", // byte order mark
            "café ", // no-break space
            "CAFÉ",
        )
        for (space in spaces) {
            rejectedFor(ScopeResolver.resolve(subject, space, noHost))
                .assert().describedAs(space).isEqualTo(ScopeResolution.Rejected.Reason.SPACE_NOT_MEMBER)
        }
        val apps = listOf(
            "оrder", // Cyrillic o
            "ｏrder", // fullwidth o, NFKC folds it to "order"
            "order​",
            "order ",
            " order",
        )
        for (app in apps) {
            rejectedFor(ScopeResolver.resolve(credential(app), "", HostScope(AppId("order"))))
                .assert().describedAs(app).isEqualTo(ScopeResolution.Rejected.Reason.APP_MISMATCH)
        }
    }

    @Test
    fun `a declared app that cannot be an app id is rejected with or without a host app`() {
        // RC1-15: a control character, which would forge a plain-text log line, as a tenant id's would.
        val controls = listOf("a\nforged", "a\rb", "\u0000", "a\u007Fb", "a\u0085b", "\t")
        // RC1-52: an unpaired surrogate, which a Redis key writes as '?', so `shop\uD800` would read `shop?`'s documents.
        val unpaired = listOf("shop\uD800", "\uDC00shop", "\uD800", "a\uDC00\uD800b")
        val invalid = listOf("*", "a:b", ":", " ", "x".repeat(ScopeResolver.MAX_APP_ID_LENGTH + 1)) + controls + unpaired
        for (host in listOf(noHost, HostScope(AppId("order")))) {
            for (app in invalid) {
                rejectedFor(ScopeResolver.resolve(credential(app), "", host))
                    .assert().describedAs(app).isEqualTo(ScopeResolution.Rejected.Reason.APP_MISMATCH)
            }
        }
        val longest = "x".repeat(ScopeResolver.MAX_APP_ID_LENGTH)
        resolved(ScopeResolver.resolve(credential(longest), "", noHost)).app.assert()
            .isEqualTo(AppId(longest))
        // A surrogate pair is one character: an app id may hold it.
        resolved(ScopeResolver.resolve(credential("shop\uD83D\uDE00"), "", noHost)).app.assert()
            .isEqualTo(AppId("shop\uD83D\uDE00"))
    }

    @Test
    fun `a near miss of the host app is rejected`() {
        for (app in listOf(" order", "order ", "ORDER", "a@b")) {
            rejectedFor(ScopeResolver.resolve(credential(app), "", HostScope(AppId("order"))))
                .assert().describedAs(app).isEqualTo(ScopeResolution.Rejected.Reason.APP_MISMATCH)
        }
    }

    @Test
    fun `blank tenants and invalid host apps cannot be given`() {
        for (tenant in listOf("", " ")) {
            runCatching { ScopeSubject(TenantId(tenant), true, memberships) }.isFailure.assert().isTrue()
        }
        for (app in listOf("", " ", "a:b", "a\nb")) {
            runCatching { HostScope(AppId(app)) }.isFailure.assert().describedAs(app).isTrue()
        }
    }

    /** RFC 0012 §3.5 (`SCP-11`): the table of the host's app against the credential's. */
    @Test
    fun `a credential is served only by a host of its app`() {
        val order = HostScope(AppId("order"))
        resolved(ScopeResolver.resolve(credential("order"), "", order)).app.assert().isEqualTo(AppId("order"))
        rejectedFor(ScopeResolver.resolve(credential("billing"), "", order))
            .assert().isEqualTo(ScopeResolution.Rejected.Reason.APP_MISMATCH)
        rejectedFor(ScopeResolver.resolve(credential(null), "", order))
            .assert().isEqualTo(ScopeResolution.Rejected.Reason.APP_MISMATCH)
        resolved(ScopeResolver.resolve(credential("order"), "", noHost)).app.assert().isEqualTo(AppId("order"))
        resolved(ScopeResolver.resolve(credential(null), "", noHost)).app.assert().isEqualTo(AppId(""))
    }

    @Test
    fun `an empty credential app is not one that was left out`() {
        rejectedFor(ScopeResolver.resolve(credential(""), "", noHost))
            .assert().isEqualTo(ScopeResolution.Rejected.Reason.APP_MISMATCH)
    }

    @Test
    fun `a credential app other than the host app is rejected`() {
        rejectedFor(ScopeResolver.resolve(credential("billing"), "", HostScope(AppId("order"))))
            .assert().isEqualTo(ScopeResolution.Rejected.Reason.APP_MISMATCH)
    }

    @Test
    fun `the app is checked before the space`() {
        rejectedFor(ScopeResolver.resolve(credential("billing"), "team-c", HostScope(AppId("order"))))
            .assert().isEqualTo(ScopeResolution.Rejected.Reason.APP_MISMATCH)
    }

    /** An anonymous subject is always in the default tenant; the resolver does not trust any other it is given. */
    @Test
    fun `an anonymous subject is in the default tenant and has no roles`() {
        val anonymous = ScopeSubject(acme, authenticated = false, memberships)
        val scope = resolved(ScopeResolver.resolve(anonymous, "", noHost))
        scope.tenant.assert().isEqualTo(TenantId.DEFAULT)
        scope.roles.assert().isEmpty()
        rejectedFor(ScopeResolver.resolve(anonymous, "team-a", noHost))
            .assert().isEqualTo(ScopeResolution.Rejected.Reason.SPACE_NOT_MEMBER)
    }

    /** RFC 0012 §3.5: an anonymous request has no credential to declare an app, so its app is the host's. */
    @Test
    fun `an anonymous subject is in the host's app and no other`() {
        val anonymous = ScopeSubject(acme, authenticated = false, memberships, app = "billing")
        resolved(ScopeResolver.resolve(anonymous, "", HostScope(AppId("order")))).app.assert().isEqualTo(AppId("order"))
        resolved(ScopeResolver.resolve(anonymous, "", noHost)).app.assert().isEqualTo(AppId(""))
    }

    @Test
    fun `rejections are shared and carry their reason`() {
        val first = ScopeResolver.resolve(user, "team-c", noHost)
        first.assert().isSameAs(ScopeResolver.resolve(user, "team-d", noHost))
        ScopeResolution.Rejected.Reason.entries.assert()
            .containsExactly(
                ScopeResolution.Rejected.Reason.APP_MISMATCH,
                ScopeResolution.Rejected.Reason.SPACE_NOT_MEMBER
            )
    }
}
