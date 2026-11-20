# G2 design: the open changes as sets, store failures as 503, a tolerant shadow pass, formats on stored records

## Hand-over (keep at the top)
- **Asked by:** the coordinator, 2026-11-20, after G3 closed.
- **Covers:** R2C-2 (first half), R2C-4, R2C-6, R2C-3, R2C-17, R2C-10, R2C-28.
- **Status:** approved 2026-11-20 with one correction (KEPT, below) and decisions: CTL-52 to CTL-56; the session rule in IDN (next free id); stopping the control plane across G2b is accepted; no `author=me`; G4 (R2C-11 to 13) becomes G2e after G2d; the record table goes into `storage.md` with G2b. Building G2a.
- **Read against:** `main` at 252a5b355. The findings are in `docs/reviews/2026-11/review-r2-3-control-storage.md`.
- **Already fixed:** R2C-2's second half (a failed unlisting no longer fails the write that landed) is done in #1745 and is not repeated here.

## 1. The open changes as sets of record (R2C-2, R2C-4)

**Today.**
- `<s>:control:list:open-changes` is one JSON map, change id to the time it was listed.
- Writes:
  - every first write of a change rewrites the map whole (read, change, `putIfMatch`, 16 attempts);
  - every terminal write rewrites it again.
- `openChanges()` reads every listed change record, with three callers:
  - reclamation, which needs every open change's revisions;
  - the shadow pass, which needs the `SHADOWING` ones;
  - nothing else, so no listing route exists (R2C-4).

**Proposal.** Two families of CoCache 8.1 `SetStore`s of record, like the back-references (#1729) and the staged list (#1742):
- local cache pinned to 1 ns (`SetsOfRecord.LOCAL_MAX_AGE`);
- an unknown read throws;
- each change is one `SADD` or `SREM`, so changes never contend.

| Set | Member | Added | Removed |
|---|---|---|---|
| `<s>:control:changes:<docKey>` | `<changeId>` | before the change's first write | after the write that makes it terminal |
| `<s>:control:changes:state:<STATE>`, one per non-terminal state | `<docKey>#<changeId>` | before the write that puts the change in `STATE` | after the next write moves it out |

- **The invariant is a superset**, as for the staged list (`CTL-46`):
  - every open change is a member of its document's set and of its current state's set;
  - a member may also be stale (a later state, terminal, or never written).
  - So a reader confirms against the change record it reads anyway.
- **Each set has a `KEPT` member** (#1742). Correction from review: **while the old `list:open-changes` exists the sets are not kept, whatever members they hold**. `KEPT` is written by the conversion, and by a control plane only when the old key does not exist (a new or converted store). An old-version control plane that writes the old key again puts the store back to not kept, which fails closed (`CTL-54`, guard and mutant). A set without `KEPT`, or any set while the old key exists, is not trusted (below).

**Write order and what a crash leaves.**
- The member is added first, then the record is written, then any member that no longer applies is removed. So a crash between two steps never loses a change from a listing; it only leaves a stale member.
- **Who repairs it:**
  - a stale state member is dropped by the next listing that reads the record (state differs);
  - a document member whose record is terminal, or missing for longer than `cosec.control.revisions.orphan-age`, is dropped by the maintenance pass (the same rule `openChanges()` applies today, per document).
- **A failed removal only warns** (as #1745 does for the list).

**What the readers read.**
- **Reclamation:** per document, `SMEMBERS changes:<docKey>`, then those change records. It already walks the document list. One unreadable or undecodable change stops reclamation **of that document only**: what that change rests on is not known, so nothing of it is reclaimed. Today one bad change stops all documents.
- **The shadow pass:** `SMEMBERS changes:state:SHADOWING`, then those records. It no longer reads every open change (section 3).
- **`GET /changes`, new:**
  - parameters: `document=<key>` or `state=<STATE>`, plus `after=<cursor>` and `limit` (default 50, at most 200);
  - it answers `{ "changes": [ {id, document, state, author, createdAt, updatedAt} ], "next": <cursor|null> }`, read from the records, not the members;
  - `document=` reads one set; `state=` reads one set (members sorted by `docKey#changeId` for a stable cursor);
  - **authorization:** a change is listed only if `GET /changes/{id}` would show it to the operator, decided per change through the same `flow.visible` check;
  - **filtering:** a tenant operator asking `state=` without `document=` is answered for their own tenant's documents only, and the filter happens before paging, so a page never hints at other tenants;
  - **an unreadable record** is listed as `{id, document, state: "UNREADABLE"}`, never dropped.

**Conversion of a store with the old value.** It is part of `cosec-migrate repair-references`, the run every store written before beta.5 already needs (`CTL-42`):
1. read `list:open-changes`;
2. for each change record that exists and is not terminal, add its document member and its state member;
3. write `KEPT` in both families;
4. delete the old key.

It holds the same lease and is idempotent.
- **Until the sets are kept**, the control plane:
  - adds members for new changes;
  - **reclaims nothing** (an unlisted old change might rest on a revision, so it fails closed);
  - answers `GET /changes` with 503 and the detail `run cosec-migrate repair-references`.
- **Cost of an upgrade:**
  - from a store before beta.5: nothing extra beyond the run already needed, plus one change read per open change;
  - from a store a beta.5 preview without this already converted: one more `repair-references` run, which reads only the old list and the open changes (O(open changes); the reference steps find nothing to do).

**Rejected.**
- *One set of `<docKey>#<state>#<changeId>` members:* a listing by document or by state then reads every member of every tenant, which is the cost R2C-4 is about.
- *Keeping the map and paging it:* still one value everyone rewrites, so the 50-concurrent-writer failure measured for `refs` applies to creating changes.
- *Sorted sets ordered by time:* CoCache 8.1 has no sorted-set store of record, and ordering by id is enough for a stable cursor.
- *A separate migration command:* operators already run `repair-references` once for beta.5; a second command is a second thing to forget.

## 2. `ControlStoreException` and 503 (R2C-6)

**Proposal.**
- **New exception:** `me.ahoo.cosec.control.store.ControlStoreException` (unchecked, `@ExperimentalCoSecApi` with the port) means "the store could not answer": a connection, a timeout, a script error, a cancelled future. `ControlStore`'s KDoc names it on every operation.
- **Where the mapping lives:** `CoCacheControlStore` wraps whatever CoCache or Redis throws in it, at its few helpers (`read`, `put`, `updateList`, the set operations, `failClosed`), except:
  - **`SnapshotFormatException` stays:** "the stored value is wrong", a 500 logged as a stored value that does not decode, as today;
  - **the old-format case of `CTL-42` stays an `IllegalStateException`** naming `repair-references`, a 500 with that ERROR line. It is not transient, and a 503 would invite retries that cannot succeed.
- **`ControlHttp`** maps `ControlStoreException` to 503 `unavailable`, logged at WARN with the request id. The 25 local `ChangeRejectedException(UNAVAILABLE)` sites can go in a later cleanup; they already answer 503.
- **Codecs:**
  - store codecs that throw `IllegalArgumentException` today (`ReportersCodec`, `InstanceIndexCodec`, `InstanceStatusCodec`, `TokenFamilyCodec`) throw `SnapshotFormatException` instead;
  - today such an exception on the control API path reads as **400 invalid request** (`ControlHttp`'s `IllegalArgumentException` branch).
- **No store failure reads as an empty answer, as a rule with a guard:** `FailingControlStore` throws `ControlStoreException` from every operation, and a test drives every route of the control API and both maintenance passes over it. Every route must answer 503, never 200 or 404. The passes must log and do nothing:
  - no window is closed;
  - nothing is reclaimed;
  - no staged member is removed.

**Rejected.**
- *Mapping by exception class in `ControlHttp` (Lettuce, Spring Data):* `core/control` must not know Redis (`ControlBoundaryTest`), and another backend would need its own list.
- *Treating every unknown exception as 503:* a bug would then read as an outage and be retried forever.

## 3. A tolerant shadow pass (R2C-3) and a lost report closes at `max-duration` (R2C-17)

**Proposal.**
- **The pass reads `changes:state:SHADOWING`** (section 1), not every open change.
- **One change it cannot read is skipped and counted**, through `attempt`:
  - **an undecodable one** logs a WARN that names it, at most once per change per hour, and the pass goes on;
  - the staged list's reconciliation is told the read was incomplete (`complete = false`, as today), so it removes nothing on that pass's evidence.
- **Fairness:** the pass starts with the window it looked at least recently: a per-instance map of window to last look, then oldest first. When `withTimeout` cuts the pass it logs `looked at k of n windows`, so a window behind the cut is reached on the next pass.
- **A report that is listed but missing, or present but undecodable, never counts as zero decisions:**
  - **Before `max-duration`:** the window stays open and `look` says `waiting for <instance>`. An instance rewrites its cumulative report every 10 s, so a report lost in a failover comes back if the instance still serves.
  - **At or past `max-duration`:** the window closes **insufficient**, with the vanished instances named in the summary (`ShadowSummary.vanished`, a new field). It never passes: an insufficient window publishes only with the acknowledgement that publishing on too little evidence needs today.
  - **The 503** of `POST /changes/{id}/shadow` for unreadable reports gets the window's id in its detail.

**Rejected.**
- *Dropping the instance whose report is lost and judging the window on the others:* that passes a window on missing evidence. A vanished instance may be the one that saw the narrowed requests.
- *Closing as `blocked`:* blocked means the evidence shows a regression; here there is none, so the right word is insufficient.

## 4. A format number on every stored record, and the reader's rule (R2C-10, R2C-28)

**The rule, one for all records.** Each record carries `"format": <n>`, a small integer. A record written before the number has none and **is read as format 1**.
1. **A known format:** unknown members are ignored, and members a reader does not find take their documented default.
2. **An unknown, higher format is refused.** What "refused" means depends on the record (table below); it is never "absent".
3. **On writes:** a member an older instance may drop when it rewrites the record is optional within a format. Anything an older instance must not drop or ignore, such as a constraint on an approval, needs **a new format number**, which older readers refuse. So an old writer can never silently remove a new constraint.

**Decision inputs stay strict:** the policy documents keep `schema: 2` and refuse unknown members (the document format's rules, `schema/`), and the referrer members keep their grammar (`CTL-42`, fail closed).

**Every stored record type of `cocache` and `control`:**

| Record (key) | Written by | Read by | Format today | Unknown members today | Proposed | "Refused" means |
|---|---|---|---|---|---|---|
| Policy document: head and revision records (`snapshot`, `control:revision:*`) | control plane | hosts, control plane | `schema: 2` | refused | unchanged (decision input) | as today: fail closed (`SNP`) |
| Change request (`control:change:<id>`) | control plane | control plane | none | refused (`only`) | `format: 1`, rule 1–3 | listed as `UNREADABLE`; `GET` 500 with a detail; its document's reclamation stops; the shadow pass skips and counts it |
| Stage slot (`control:stage:<doc>`) | control plane | control plane | none | refused | `format: 1` | the slot is taken as held: no new stage of that document (fail closed); `abandon` and expiry still free it |
| Revision index (`control:revisions:<doc>`) | control plane | control plane, migrate | none | refused (a missing `parent` is tolerated) | `format: 1` | that document's publish and history are a 500, as today |
| App list (`control:apps:all`) | control plane | control plane | none | refused | `format: 1` | untrusted: every app a grant reaches is unknown (`SCP-10`), as today |
| Open-change list (`control:list:open-changes`) | control plane | control plane | none | n/a | removed (section 1) | n/a |
| Back-reference members (`control:refs:*`) | control plane | control plane | member grammar | refused | unchanged (`CTL-42`) | the set is unreadable, fail closed |
| Document list, open-change and staged members | control plane | control plane, hosts | member grammar | staged: skipped (#1742); documents: refused | open-change members as staged ones: an unknown member is skipped and counted, never trusted as a change | skipped |
| Leases (`control:lease:<name>`) | control plane, migrate | the same | text `holder since instant` | n/a | unchanged (expires) | n/a |
| Staged candidate (`candidate:<doc>`) | control plane | hosts | none | refused (`only`) | `format: 1` | the host skips it and reports nothing for the window, so the window cannot pass on it |
| Shadow report (`shadow:report:<window>:<instance>`) | hosts | control plane | none | refused | `format: 1` | read as lost (section 3) |
| Reporters (`shadow:reporters:<window>`) | hosts | control plane | none (JSON array) | `require`, 400 on the API path | `format: 1` object | the window waits, then closes insufficient |
| Instance status (`shadow:status:*`) and its index | hosts | control plane | `formatLevel`, required | refused | `format: 1`; `formatLevel` stays the publish gate | the instance counts as not reporting its level: the gate fails closed, as today |
| Token family, the session (`token:family:<id>`) | login host | login host | none | refused (exact field set) | `format: 1`, rule 1–3 (R2C-28) | the session ends: the refresh is refused as an invalid token, and the user signs in again |
| API key (`apikey:*`) | control or ops | hosts | `format: 3` | refused | unchanged and strict (decided with #1766: an input of authentication, where an ignored member could be a restriction) | the key is refused, as today |
| Memberships cache | hosts | hosts | none | refused | unchanged (TTL cache) | a miss: reloaded from the provider |
| Revoked tokens and subjects | revocation | hosts | scalar | n/a | unchanged | as today (`IDN-14`) |
| Unknown principals, social sign-in states (#1757) | hosts | hosts | scalar or text, short TTL | n/a | unchanged | a miss |
| Rate-limit counters | hosts (script) | hosts | counters | n/a | unchanged | n/a |

**A rolling upgrade.**
- The first release with the rule writes `format: 1`. Readers of the previews before it refuse the new member, because they are strict.
- So an upgrade from such a preview is "stop the control plane, upgrade, start": the control plane is one deployment.
- Data-plane hosts that refuse a `format`ted candidate during the roll report nothing for that window, which only makes it wait.
- **From GA on:** a minor release adds optional members within format 1, and both directions read them. A must-understand change bumps the format, and the older instances refuse it as the table says.

**A rollback.**
- Within format 1 it works.
- Across a format bump, the refusals in the table apply: a change becomes unreadable, a session ends. `upgrading.md` says so for each bump.

**Rejected.**
- *Tolerating unknown members without a number:* no way to say "you must not drop this".
- *A number only in the store key (`v2:`):* it doubles the keys, and a rolling upgrade reads two places.
- *Making the policy documents tolerant too:* they are decision inputs, and an ignored member could widen access.

## 5. What breaks, and the PRs

**What breaks:**
- **Control API:** a store failure is 503 where it was 500, and a stored value of the wrong kind is 500 where some were 400. `GET /changes` is added.
- **Stored shapes:** `list:open-changes` is replaced by the sets. `format` is added to the records of the table, and the reporters value becomes an object. `ShadowSummary.vanished` is added.
- **Port:** `ControlStore.openChanges()` becomes:
  - `openChanges(document)` and `changes(state, after, limit)`;
  - member operations for the two set families;
  - `ControlStoreException` named on every operation.
- **Audit JSON:** unchanged, apart from `vanished` in a closed window's summary.

**The order I would build it in:**

| PR | Content | Rules (to be given) | Why first |
|---|---|---|---|
| G2a `fix(control)!` | `ControlStoreException`, its 503 mapping, codecs throw `SnapshotFormatException`; the "no failure reads as empty" guard | `CTL-52` | small, and every later PR's error paths use it |
| G2b `feat(cocache,control)!` | `format` on every record of the table, the reader rule; the session record (R2C-28) | `CTL-53` (control records), one for the session record (`IDN-…`?) | the sets of G2c are written with `format` from their first day |
| G2c `feat(control,cocache,migrate)!` | the two set families, the write order, reclamation per document, the conversion in `repair-references`, `GET /changes` with paging and per-change visibility | `CTL-54` (the superset and write order), `CTL-55` (a listing shows only what `GET /changes/{id}` would) | the stored-layout change, before RC |
| G2d `fix(control)` | the shadow pass over `SHADOWING`, skip and count, fairness; a lost report closes insufficient at `max-duration` | `CTL-56` (no window passes on missing evidence) | needs the state set of G2c |

**Questions for the coordinator:**
- (a) The rule ids above, and whether the session record's rule goes in `IDN` or `CTL`.
- (b) Whether "stop the control plane to upgrade from the preview before" is acceptable for G2b, or whether its readers should first ship tolerant without writing `format`. That would be one more preview.
- (c) Whether `GET /changes` should also offer `author=me`. I left it out: `document=` covers a tenant administrator, and `state=AWAITING_APPROVAL` covers an approver.

## G2c status (2026-11-20, 16:58 UTC)
- PR #1769: every checklist item done. Main (#1766) merged in. The mutant run is dispatched (run 44778148847); CI is pending.
- A fix found while documenting: a listing by state used to cut the store's page before filtering, so `next` could name another tenant's change. Now:
  - `ControlStore.changes` takes a `documents` filter, so a tenant caller never reads other tenants' records;
  - the listing reads until it has limit + 1 visible changes;
  - the guard is "a cursor names only a change the caller sees… - CTL-55".
