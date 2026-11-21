# G2e: the control API's answer shapes (R2C-11, R2C-12, R2C-13): proposal

Branch `fix/control-api-shapes` (from origin/main 7c655ac49). Nothing is built yet; four decisions are asked at the end.

## What is answered today (read from the code)

- **A change** (`GET /changes/{id}`, and every route that answers one: propose, `apply`, `rollback`, `rebase`, `shadow`,
  `skip-shadow`, `approve`, `reject`, `abandon`, `publish`, break-glass request and approval) is
  `ChangeRequestCodec.encodeShown`: the stored tree minus `format`, with the approval cut to four fields. So the answer also
  carries `checkedWith` (a map from document key to digest), `checkedApps.digest`, and `shadow.reporters`. Those are the
  store's bookkeeping: what the publish step checks again, and which instances reported.
- **A decision** (`simulate`, `explain`): `cause` is `decision.cause::class.simpleName` for `Indeterminate`
  (`MatcherFailed`, `SourceUnavailable`, …), and the `ScopeResolution` name (`APP_MISMATCH`, `SPACE_NOT_MEMBER`) for
  `Rejected`. Those are two vocabularies in one member. The testkit's scenario format already calls the second one
  `reason`.
- **`propagation`**: `{revision, installed, recent}`.
  - `installed` counts the recent instances whose status lists the document at that revision or later.
  - `recent` counts every instance with a status in the last minute.
  - Each call reads every status (about 47 KB each, so about 2.4 MB at 50 replicas), with no cache.
- **TypeScript:** `typescript/cosec-fetcher` never calls the control API, and `contract/` holds only the data plane's
  files. The fetcher's tests read `contract/problems/` by directory, so a new `contract/control/` directory does not
  reach them.

## Proposal

### R2C-11, part 1: a response model of its own

`ChangeView`, written by an answer codec of the control API (in `api/`), never by the record codec. The idea is that a
change to the record no longer changes what a client reads.

**Members kept, with today's names and types:**
- `id`, `key`, `base{revision,digest}`, `operations`, `author`, `note`, `kind`, `state`
- `findings[]{check,path,message,status,severity}`
- `candidate`, `createdAt`, `updatedAt`, `skipShadow`, `publishedRevision`
- `escalations[]{subject,reason,detail}`
- `approval{approver,at,candidate,covers}`
- `confirmations[]`
- `shadow{window,startedAt,closed{closedAt,insufficient,blocked,counts,vanished}}`. A publisher sends `window` back in
  its acknowledgement.

**Members removed from the answer:**
- `checkedWith`
- `checkedApps`
- `shadow.reporters`

The change event's JSON is the audit stream's, not this one, and is left as it is.

### R2C-11, part 2: the closed vocabularies come from explicit tables

The tables are exhaustive `when` expressions, so a new `Cause` that is missing from one fails the build, and renaming a
class changes nothing on the wire.
- **`cause` on `Indeterminate`:** today's spellings (`MatcherFailed`, `MissingPolicies`, `AmbiguousPolicy`,
  `SourceUnavailable`, `DocumentInvalid`, `NotLoaded`, `BoundaryUnavailable`, `AttributeUnavailable`, `CheckFailed`,
  `MembershipUnavailable`, `RequiredListenerFailed`, `RateLimiterUnavailable`).
- **`reason` on `Rejected`:** `APP_MISMATCH` or `SPACE_NOT_MEMBER`, moved out of `cause`.

### R2C-11, part 3: pinned the way `contract/` pins the data plane

- **`contract/control/change.json`:** each member of a change answer, with its JSON type and its presence (`always` or
  `optional`).
- **`contract/control/vocabularies.json`:** the closed sets, which are:
  - `state`, `kind`, `severity`, `escalation reason`;
  - `decision` (`Permit`, `Deny`, `Indeterminate`, `Rejected`);
  - `cause`, `reason`;
  - the trace's `effect` and `outcome`.
- **Checked by** a new `control:ControlContractTest`, and rewritten by `:control:updateClientContract`.
- **Docs:** a row in `contract/README.md`.
- **Change summaries:** the `GET /changes` listing (new in G2c, not yet released) renames `document` to `key`, so that a
  summary's members are a subset of the change answer's. The `document=` query parameter stays.

### R2C-12: `holding`

- **The answer:** `{revision, installed, holding, recent}`.
  - `holding` (new): the recent instances whose status lists the document at any revision.
  - `installed` and `recent`: same meaning as today.
  - This is additive: no existing member's meaning changes.
- **What the docs will say:** a tenant document is propagated when `installed == holding`. A tenant document that no
  instance holds (`holding: 0`) is not propagated yet. It is loaded on its first request.
- **Caching:** statuses are read at most once every 5 seconds per control host. One read is shared by concurrent calls.
  - This is not configurable. A status older than a minute is ignored anyway.

### R2C-13: one step, one name

- **The wire stays as it is:** `:shadow`, `SHADOW_STARTED`, `SHADOWING`.
- **The flow's entry points are renamed to match:**
  - `Changes.stage` becomes `Changes.startShadow`;
  - `Staging.stage` becomes `Staging.startShadow`;
  - `ControlPlane.startShadow` already matches.
- **"Staging slot" stays:** the RFC 0007 §5.5 name for the slot that a change starting its shadow window takes. The
  page gets one sentence that ties the names together: "`:shadow` (audit `SHADOW_STARTED`, state `SHADOWING`) takes the
  document's staging slot".
- **The `ControlStore` port's `stage`, `putStage`, `stagedCandidate`, the staged list and the Redis keys are not
  renamed.** They are an experimental port with a backend, and the staged list (`CTL-46`) is read by the data plane.
- **The test for R2C-13:** `contract/control/routes.json` pins each route verb with the audit kind it writes. The test
  drives each verb through `ControlHttp` and compares the kind it audits.
- **The mutant for R2C-13:** it maps `:shadow` to another plane method.

## Decisions asked

1. **R2C-11:** remove `checkedWith`, `checkedApps` and `shadow.reporters` from the change answer. Keep the other members
   under today's names.
2. **R2C-11:** keep today's `cause` spellings, now produced from an explicit table, and move `Rejected`'s value to
   `reason`. The alternative is new kebab slugs (`matcher-failed`, …), which breaks every client that reads `cause` and
   gains nothing over a pinned table.
3. **R2C-11:** pin the control API in a new `contract/control/` directory, rather than in a test resource of `control`.
   Rename the listing's `document` to `key`.
4. **R2C-12:** the new member's name is `holding`.

**Rule ids:** I would take `CTL-61` for the answers coming from the response model and the pinned vocabularies, and
`CTL-62` for "propagation counts only the instances that hold the document".
