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

package me.ahoo.cosec.cocache

/**
 * What instances of two releases read and write on one Redis, frozen within 8.x (RFC 0014 R6, `SNP-26`): how each key and
 * channel is derived from its inputs, what each stored field means, how a counter's window is cut. Two releases that
 * derive one of them differently each read and write their own copy, so a mixed deployment either lets through what
 * both releases refuse ([IfChanged.WIDENS]) or refuses what both accept ([IfChanged.REFUSES]). A new derivation, field
 * or way of counting goes to a new key space, written only once every reader can read it (R5).
 *
 * The one list of them. Each entry is held by golden samples, fixed inputs to fixed outputs (`FrozenLayoutTest`, and
 * `FrozenScriptsTest` for what a script stores). The code that derives them may change, a script's text included, so
 * long as the samples do not.
 */
internal enum class FrozenLayout(val what: String, val ifChanged: IfChanged) {
    /**
     * `<s>:rate-limiter:<ruleId>:<bucket>` ([CoSecCacheSpecs.rateLimiterPrefix],
     * `ReactiveRedisRateLimiter.keyOf`), the bucket `me.ahoo.cosec.engine.CompiledRateLimit.keyedBucket`'s (`k:`, `e:`,
     * `h:`), and what `redis-rate-limiter.lua` stores: the hash fields `w` (the window's number, `floor(now / window)` on
     * Redis's `TIME`), `c` and `p` (this window's count and the last's), expiring after two windows. Two releases that
     * count one client apart give it a quota from each.
     */
    RATE_LIMIT_COUNTERS("a rate limit counter: its key, its bucket and the fields its script stores", IfChanged.WIDENS),

    /** `<s>:token:revoked:<jti>` ([RevokedTokenCache.key]): a revocation one release writes and the other cannot find lets its token through. */
    REVOKED_TOKEN("the key of a token's revocation", IfChanged.WIDENS),

    /** `<s>:token:subject:revoked:<n>:<tenant>:<principal>` ([RevokedSubjectCache.key]), holding the time before which every token is revoked. */
    REVOKED_SUBJECT("the key of a principal's revocation", IfChanged.WIDENS),

    /**
     * `<s>:api-key:<sha256>` ([CoSecCacheSpecs.apiKeyPrefix], [RedisApiKeyStore.redisKey], the lower-case hex SHA-256
     * of the key's UTF-8, `me.ahoo.cosec.spi.apikey.ApiKeys.hash`). Revoking a key deletes this one: a release that
     * derives it otherwise deletes nothing the other reads, and the revoked key goes on working there.
     */
    API_KEY("the key of an API key's record", IfChanged.WIDENS),

    /**
     * `<s>:token:inv:revoked` and `<s>:token:subject:inv:revoked`, CoCache's `{keyPrefix}inv:{namespace}` of the two
     * revocation caches: a frame the other release does not hear leaves its local "not revoked" for up to
     * [RevokedTokenCache.MISSING_TTL_SECONDS].
     */
    REVOCATION_CHANNELS("the invalidation channels of the revocations", IfChanged.WIDENS),

    /** `<s>:inv:membership` ([CoSecCacheSpecs.membershipPath], [MembershipInvalidations]): an instance that misses a frame keeps a principal's old roles locally. */
    MEMBERSHIP_CHANNEL("the invalidation channel of the memberships", IfChanged.WIDENS),

    /**
     * `<s>:snapshot:<platform | app:<appId> | tenant:<tenantId>>` (`SnapshotKeyEncoder`) and its channel
     * `<s>:inv:snapshot`. An app or tenant document the other release cannot find reads as no document, and its DENYs
     * stop applying; a platform document it cannot find fails every request.
     */
    DOCUMENTS("the keys of the snapshot documents and their invalidation channel", IfChanged.WIDENS),

    /**
     * `<s>:token:family:<sid>`: a session another release cannot find is `token-invalid`, and one whose end (a logout,
     * a reuse) is written where the other does not read goes on refreshing there.
     */
    TOKEN_FAMILY("the key of a session's record", IfChanged.WIDENS),

    /**
     * `<s>:token:sessions:<n>:<tenant>:<principal>` ([CoSecCacheSpecs.sessionIndexPrefix], [RedisSessionIndex]) and
     * what `session-index.lua` stores: a sorted set of session ids scored by Redis's `TIME` in milliseconds, increasing,
     * the newest [me.ahoo.cosec.spi.token.TokenFamilyStore.SESSIONS_PER_PRINCIPAL] kept. Two indexes each keep that many.
     */
    SESSION_INDEX("the index of a principal's sessions: its key and what its script stores", IfChanged.WIDENS),

    /**
     * `<s>:oauth:binding:<state>` ([CoSecCacheSpecs.socialBindingPrefix], [RedisSocialStateStore], taken once with
     * `GETDEL`) and JustAuth's `<s>:oauth:state:<state>` (`RedisAuthStateCache.keyPrefix` of `cosec-social`): a callback
     * on the other release finds no state, and the sign-in fails.
     */
    SOCIAL_STATE("the keys of a social sign-in in progress", IfChanged.REFUSES),

    /**
     * `<s>:oauth:binding:!count` ([RedisSocialStateStore.COUNT_SUFFIX]) and what `social-states.lua` stores: a hash
     * whose fields are the window's number in decimal, `floor(now / window)` seconds on Redis's `TIME`, the last two
     * summed against the bound. Two releases counting apart allow twice the sign-ins in progress.
     */
    SOCIAL_COUNT("the count of social sign-ins in progress: its key and what its script stores", IfChanged.WIDENS),

    /**
     * `<s>:shadow:status:<instance>`, `<s>:shadow:resident:<instance>` and `<s>:shadow:instances:all`: the instances the
     * control plane can see read the store (R5). One it cannot see is left out of the readers a publish waits for.
     */
    INSTANCE_STATUS(
        "the keys of an instance's status, its resident documents and the list of instances",
        IfChanged.REFUSES
    ),

    // What the replicas of the control plane share: two replicas of two releases run side by side during a rolling
    // upgrade, and each writes what the other reads (`cosec-migrate` too).

    /**
     * `<s>:control:revision:<doc>:<digest>` (`RevisionRecordKeyEncoder`), `<s>:control:revisions:<doc>` and their channel
     * `<s>:control:inv:revision`. A publish writes the head conditionally on the index it read (`CTL-4`): two replicas
     * that each read their own index compare against a record the other never wrote, and one publishes over the other,
     * whose revision, its DENYs included, is lost; a rollback finds no revision the other wrote.
     */
    CONTROL_REVISIONS("the keys of a document's revisions and of its revision index", IfChanged.WIDENS),

    /**
     * `<s>:control:stage:<doc>`: the one change that may stage a document (RFC 0007 §5.5). Two slots let two changes
     * stage and publish one document at once, the second over the first.
     */
    CONTROL_STAGE("the key of a document's staging slot", IfChanged.WIDENS),

    /**
     * `<s>:control:change:<id>` and the sets of open and ended changes, `<s>:control:changes:doc:<doc>`,
     * `:state:<STATE>`, `:kept` and `:ended:<doc>` (`OpenChangeSets`): a replica cannot find a change the other proposed,
     * so it cannot approve, publish or list it, and each counts its own open changes against the limit (`CTL-83`).
     */
    CONTROL_CHANGES("the keys of a change and of the sets of open and ended changes", IfChanged.REFUSES),

    /**
     * `<s>:control:refs:<doc>:<policyId>` (`ReferenceKeyEncoder`) and its channel `<s>:control:inv:refs`: a replica that
     * cannot see who refers to a platform policy removes it while a tenant still binds it, and that tenant's requests
     * are Indeterminate (503) until the binding goes.
     */
    CONTROL_REFERENCES("the keys of a policy's back-references", IfChanged.REFUSES),

    /**
     * `<s>:control:documents:all`, `<s>:control:apps:all` and their channel `<s>:control:inv:documents`: a replica that
     * reads another list lists fewer documents, and finds the app list incomplete, so every tenant grant over every app
     * waits for a person (`UNREADABLE`).
     */
    CONTROL_LISTS("the keys of the document list and of the app list", IfChanged.REFUSES),

    /**
     * `<s>:control:list:open-changes` and `<s>:control:list:documents`, with their channel `<s>:control:inv:list`: the
     * lists of the format before 8.0.0-beta.5, which 8.x still reads. While one is there the store is not converted
     * (the open-change sets are not trusted, `CTL-54`) or not repaired (`MIG-4`), so an older control plane that writes
     * one again is noticed. Its value is a **bare list** (`ListedCodec`): a JSON object of entry to the time it was
     * listed, with no `format` and no other member, so it has no room for a must-understand marker and every member is
     * read as an entry. It cannot evolve by adding a member; a changed shape needs a new key space. A replica that
     * reads another key or shape does not see the old list, trusts sets an older replica no longer writes, and lists
     * fewer changes and documents.
     */
    LEGACY_LISTS("the keys and the bare value of the lists before 8.0.0-beta.5", IfChanged.REFUSES),

    /**
     * `<s>:control:lease:<name>` (`shadow-windows`, `reclaim-pass`): the exclusion a lease exists for is lost, and two
     * replicas close the same shadow windows, or reclaim the same revisions, at once.
     */
    CONTROL_LEASES("the key of a control-plane lease", IfChanged.WIDENS),

    /**
     * `<s>:candidate:<doc>`, `<s>:candidate-staged:all` and their channel `<s>:inv:candidate`: a data-plane instance that
     * reads another key finds no candidate, compares nothing and acknowledges no window, which then never passes on its
     * traffic.
     */
    CANDIDATES("the keys of a staged candidate and of the staged list", IfChanged.REFUSES),

    /**
     * `<s>:shadow:report:<window>:<instance>`, `<s>:shadow:reporters:<window>` and their channel `<s>:shadow:inv:report`:
     * a window whose reports the control plane reads elsewhere has no evidence, and waits or closes insufficient.
     */
    SHADOW_REPORTS("the keys of a shadow window's reports and of who reported", IfChanged.REFUSES),
    ;

    /** What a mixed deployment does when two releases derive an entry apart. */
    enum class IfChanged {
        /** It lets through what both releases refuse, or counts past what either allows. */
        WIDENS,

        /** It refuses, or cannot see, what both releases accept. */
        REFUSES,
    }
}
