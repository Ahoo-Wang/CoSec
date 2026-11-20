# Design D2: the staged list, the acknowledgement, and sampling only what can give a sample

## Hand-over (keep at the top)
- **Asked by:** the coordinator, 2026-11-19/20. This is a design for review before any code; nothing is implemented.
- **Inputs:**
  - section 9 of `saas-scale-data-plane.md`: the measurement, and recommendations 1, 2 and 4;
  - section 8 of `saas-referrers-design.md`, by the control-plane session: the member set, the write order, the reconciliation. Taken as input, not as decided.
- **Status:** written 2026-11-20 against `main` at 1227fb8f1 (D1 merged). Approved by the coordinator on 2026-11-20 with four conditions (section 9), which override sections 2 to 5 where they differ.
- **Order:** after #1731, the three PRs of section 7 in order. They do not hold beta.5. PR 1 waits for the control-plane session's PR B (#1729), whose `SetStore` specs, `local-max-age` lock and pinning test it reuses.
- **Rule ids:** `CTL-46` (the list), `CTL-47` (how an instance reads it), `OBS-8` (the meter); `CTL-10`, `CTL-12` and `CTL-16` amended as section 6 says.
- **Harness:** `saas-shadow-cost-harness.kt.txt` beside this file, never committed; its output is at the end of `saas-scale-raw-output.txt`.

## 1. What it is for (measured)

**Reads (section 9; the candidate cache's bound became 1,024 in D1):**
- A gateway replica sampling 10 % of 5,000 requests per second reads `<p>:candidate:<docKey>` about 450 times a second (90 per 1,000 requests). Nearly all of those keys do not exist.
- This grows with traffic and with the sample rate, and not at all with how many windows are open.

**CPU per request.** Pipeline over the in-memory store, one decided request repeated 400,000 times after 200,000 to warm up, one niced JVM, second of two rounds:

| Listeners | Caller ns/op | Caller B/op | Process CPU ns/op |
|---|---|---|---|
| none | 554 | 3,176 | 570 |
| a no-op best-effort listener (stands for the image's metrics listener) | 600 | 3,544 | 686 |
| no-op + shadow at 10 %, no window open | 608 | 3,588 | 703 |
| no-op + shadow at 0 % | 601 | 3,568 | 682 |
| shadow at 10 % alone | 600 | 3,564 | 700 |

- **Next to another best-effort listener,** as in the image with Micrometer: the shadow listener with no window open costs about 8 ns on the caller and 17 ns of process CPU per request, about 2.5 %. That is a `ReplayHandle` on a tenth of the requests, its queue offer, and three candidate lookups per sampled request on the consumer.
- **When it is the only best-effort listener:** it is also why every request makes a `DecisionEvent` at all, about 130 ns of CPU and 400 B over no listener. The first round, still warming, showed the same order.
- **What this design removes, with no window open:** all of it, and every candidate read. With windows open it keeps the work to the requests that can give a sample.

## 2. The staged list

**Key and value.**
- One value, `<p>:candidate:staged`, written by the control plane only.
- One member per staged candidate, `<docKey>@<window>` (for example `tenant:acme@w-01JF…`), as section 8 proposes.
- The window id lets an instance tell a new window of a document from the one it already read.
- It is a CoCache `Store` (store of record, never expires, a failed read throws), like `list:documents`, so an instance reads it with a conditional read (`getIfChanged` on its digest).
- **Size:** about 40 B per open window, a few kilobytes at most.

**The ACL needs no change.**
- `<p>:candidate:staged` is under the candidate namespace, which the data-plane user already reads (`%R~cosec:candidate:*`, storage.md's ACL lines).
- No document key can be `staged`: document keys are `platform`, `app:<id>` and `tenant:<id>`.
- The control-plane user writes every key under `<s>:` already.
- `storage.md`'s key table gains one row, and its "who may write" column says the control plane.

**Absent versus empty.**
- **Absent:** no control plane maintains the list (one older than this change, or none started yet). An instance then reads candidates by key, as today; the candidate cache with D1's 1,024 entries stays as the fallback.
- **Present and empty:** nothing is staged. An instance samples nothing and reads no candidate.
- **The control plane writes the value at startup if it is absent,** as the empty set plus whatever it reconciles (below). So a deployment upgraded as a whole moves to "present" on its first control-plane start.
- **A downgrade of the control plane** to a version that does not maintain the list must delete the key. Otherwise instances would trust a list nobody updates. `upgrading-previews.md` says so; `cosec-migrate` could gain a `forget-staged-list` command, which is optional.

## 3. Who writes it, and in what order (control plane)

This takes section 8's order, which keeps the list a superset of what is staged:

| Event | First | Then |
|---|---|---|
| A stage (`StagingSlots.place`) | add the member `<docKey>@<window>` | write the candidate (`putCandidate`) |
| A window closes, or its change is published, abandoned, rejected, expired or preempted (`StagingSlots.withdraw`) | withdraw the candidate (`withdrawCandidate`, conditional on the window, `CTL-26`) | remove that member |

- **A crash between the two steps** leaves a member with no candidate, never a candidate with no member. An instance that reads such a member reads the candidate key, finds none, and samples nothing for it: today's behaviour for a document with no candidate.
- **A member add that fails fails the stage** (409, nothing staged), so a candidate is never written unlisted. A member removal that fails is only logged; reconciliation removes it.
- **Reconciliation in the minute shadow pass,** under `SHADOW_LEASE` (`ControlMaintenance`):
  - add the member of every `SHADOWING` change whose candidate is written and not listed;
  - remove members whose document has no staged candidate of that window, older than twice `check-interval` (longer than one staging step).
  - A member wrongly present costs each instance one absent-key read per refresh. One wrongly absent costs samples, never a verdict (section 5).
- **Writes** are a read-modify-write with `putIfMatch` over the value read and bounded retries, as the control plane's other lists are.

## 4. The port and the data plane

**The port** (`cosec-spi`, `@CoSecSpi @ExperimentalCoSecApi fun interface CandidateStore`):

```kotlin
/** The documents with a staged candidate and their windows; null when no list is kept (read every key, as before). */
suspend fun staged(): StagedCandidates? = null
```

- `StagedCandidates` is a small value in `spi.shadow`: a set of `(SnapshotKey, window)` and the digest it was read at.
- The default body keeps every existing implementation compiling and behaving as today.
- The build uses `-Xjvm-default=all-compatibility`, so a Java implementer is unaffected too.
- **Breaking:** no. One added line in `spi.api`; the port is experimental.

**`CoCacheCandidateStore.staged()`:** a conditional read of `<p>:candidate:staged` from Redis, answered from a local copy kept current by CoCache's invalidation of that key and re-read every `report-interval` (10 s).
- **Bound:** an instance learns a new window within one invalidation, at most `report-interval` if the message is lost.

**`CachingCandidateSource`:**
- **With a list:** a key with no member answers "none" without a read. A key with a member reads its candidate as today; the cache and the coalesced reads come from D1.
- **With no list** (`staged()` answers null): unchanged.
- **Cost:** about one conditional read per replica per 10 s, plus one candidate read per open window per 10 s while traffic samples it. At most 10/s per replica for 100 open windows, 0.1/s with none, whatever the traffic.

**Sampling only what can give a sample (`ShadowListener.sample`).**
- Today `sample()` is a coin toss before the event is made (`PublishBestEffort.replay`).
- It becomes: the request's view reads a document that has a member (the platform, its app, its tenant: three lookups in an immutable set held by a volatile reference, no I/O), *and* the coin toss at `sample-rate`.
- **With no window open,** no request carries a `ReplayHandle` and nothing is evaluated again.
- **If the shadow listener is the only best-effort listener** and samples nothing, `PublishBestEffort` makes no event, saving the event's 130 ns and 400 B per request measured above.
- **`sample-rate` then applies only to requests that can give a sample.** A small tenant's window fills sooner at the same rate, and the default can stay 0.1.

**Compile on sight (strengthens `CTL-12`).**
- When an instance first sees a member of a window, it reads and compiles that candidate at once, off the request path, instead of at the first sampled request.
- A candidate it cannot compile is recorded at once as `CANDIDATE_ERROR`, which blocks the window.
- Every live instance thus proves it can compile the candidate, whatever its traffic. Today only an instance that samples a request of that document does.

## 5. The acknowledgement and the closing rule

**What exists today** (`ShadowWindows.look`, `CTL-10`, `CTL-12`, `CTL-16`):
- A window's numbers are the sum of one cumulative report per instance that has reported, each holding explicit outcome counts.
- It closes at `min-duration` with `min-samples` effective samples, at `max-duration` as insufficient, or at once on a `CANDIDATE_ERROR`.
- An instance that never reported is simply absent. "Never saw the window" and "saw it, compared nothing yet" cannot be told apart.

**The acknowledgement.**
- `InstanceStatus` (written every `report-interval` already) gains `windows: Set<String>?`: the windows of the list the instance holds, each compiled (or reported `CANDIDATE_ERROR`).
- `null` means an instance that does not send it: an older version, or no list.
- The status codec gains an optional field (RFC 0010 C5 rules: added, optional, carried by older readers as unknown).

**The closing rule** (amends `CTL-10`):
- A window may close as passed, or as passed-with-acknowledgement-required, only when every instance with a status in the last minute (`ShadowReportReader.statuses`, as `propagation` reads them) lists it in `windows`.
- At `max-duration` it closes as insufficient whatever the acknowledgements say, as today.
- A `CANDIDATE_ERROR` still blocks it at once.
- **What it fixes:** an instance whose list lost the window (a lost message, a member wrongly absent) holds the window open until it sees it, instead of letting it close without having compared.
- **What stays:** samples still come only from reports. "Not in the list" and "acknowledged" never add an outcome. Section 8's rule is kept: no path reports agreement without evaluating.

**During a rolling upgrade:**
- An instance whose status has `windows = null` counts as not acknowledged, so windows opened during the upgrade wait for it.
- Once every instance of the old version has gone, their statuses expire within 2 minutes and the rule applies to the new ones only.
- A window that cannot wait closes at `max-duration` as insufficient, which the publisher acknowledges (`CTL-11`), as today.
- **The cost:** windows opened during a rolling upgrade close no earlier than the upgrade's end. I think that is right: a version that cannot say it saw a window has not shown it can compile the candidate.

**Instances that start during a window:** a new process (a new id, `CTL-45`) reads the list, compiles and acknowledges within one `report-interval`. A window is held up by it for seconds, not more.

## 6. Rules

| Rule | Change |
|---|---|
| `CTL-10` | Amended: a window closes as passed only when every instance with a recent status acknowledges it; at `max-duration` as insufficient, as before. |
| `CTL-12` | Amended: an instance compiles a staged candidate when it first sees its window, so a `CANDIDATE_ERROR` does not wait for traffic. |
| `CTL-16` | Unchanged: the numbers are the sum of one cumulative report per instance. One sentence is added: an acknowledgement adds no outcome. |
| `CTL-26` | Unchanged; the list's removal follows the conditional withdrawal. |
| new `CTL-4x` (list) | The list is a superset of what is staged: a member is added before its candidate is written and removed after its candidate is withdrawn; a failed add fails the stage; the shadow pass reconciles both ways. |
| new `CTL-4y` (data plane) | With a list, an instance reads candidates only for listed documents and samples only requests whose view reads one; without one (absent key), it reads by key as before; an empty list means nothing is staged. |
| `SNP-18` | Unchanged; the list is read-only for a data-plane process, under the existing read-only candidate pattern. |
| `OBS-*` | A meter for the list's reads and staleness (`cosec.shadow.staged.age`). The id comes from you if wanted. |

## 7. The work

Three PRs, in this order, each green on its own:

1. **The list and its writes (control plane + cocache + spi):**
   - `CandidateStore.staged()` and `StagedCandidates`;
   - the store and codec for `<p>:candidate:staged`;
   - the write order in `StagingSlots`, the reconciliation in `ControlMaintenance`, the startup write of the empty value;
   - `CTL-4x` with scenarios, a mutant (the member removed before the withdrawal), `storage.md`.
   - About 600 lines with tests.
2. **The data plane:**
   - `CoCacheCandidateStore.staged()` with its conditional read;
   - `CachingCandidateSource` and compile on sight;
   - `sample()` gated on the view's documents, and `PublishBestEffort` making no event when nothing samples and nothing else listens;
   - `CTL-4y`, the `CTL-12` amendment, mutants, jmh-ab `PipelineBenchmark` (a `shadow` scenario added beside `listener`).
   - About 500 lines.
3. **The acknowledgement:**
   - `InstanceStatus.windows` and its codec;
   - the closing rule in `ShadowWindows`, the rolling-upgrade case, the `CTL-10` amendment with scenarios;
   - `policies/testing.md`, `upgrading-previews.md`.
   - About 400 lines.

PR 1 can land alone: the list is written and nobody reads it yet. PR 2 then removes the reads and the per-request work. PR 3 closes the "never saw it" gap.

## 8. Decisions for you
1. **The closing rule waits for every live instance's acknowledgement** (section 5), so windows opened during a rolling upgrade close no earlier than its end. The alternative: acknowledgement only from instances that send the field, which keeps today's gap for old versions.
2. **Compile on sight:** every instance compiles every staged candidate once, whether or not its traffic reads that document. The cost is one compilation per window per instance; the gain is that `CANDIDATE_ERROR` no longer depends on traffic.
3. **No event when only the shadow listener listens and it samples nothing:** a change to `PublishBestEffort`. It matters only for hosts without metrics, tracing or audit listeners.

## 9. After review (2026-11-20): decisions and conditions

**Decisions, all three yes:**
1. **The closing rule waits for every live instance's acknowledgement**, with no softening between previews.
2. **Compile on sight.**
3. **No event when only the shadow listener listens and it samples nothing,** for the best-effort publisher only. A REQUIRED listener's events are untouched, and a test says so.

**Condition 1, concurrent stages.**
- The list becomes the control-plane session's member set: `SetStore` from #1729, with an unconditional, idempotent add and remove of `<docKey>@<window>`.
- This replaces section 3's conditional replace, which loses races under a burst of stages.
- The data plane reads the whole set every `report-interval` and on its invalidation, with no conditional read: it is a few kilobytes.
- **Test:** fifty concurrent stages all land.

**Condition 2, compile on sight never fails a load closed.**
- **Today:** a candidate compiles inline, on the shadow listener's consumer (`CachingCandidateSource.compiled` → `PlatformCompiler.compileCandidate` → the compiler, on `Dispatchers.Default`), never on the snapshot source's compile pool or its 64-entry queue.
- **Compile on sight keeps that:** candidates compile one at a time, on the reporter's own coroutine, never through `SnapshotSource`'s executor.
- **Guard:** a hundred windows staged at once while documents load cold; no load fails, and the compile queue never refuses.

**Condition 3, why a window does not close.**
- The window's state in the control API gains `acknowledged` (how many live instances) and `waitingFor` (the live instance ids that have not acknowledged, at most 20, with the count of the rest).
- So "waiting for instance x" is told apart from "waiting for samples".
- `policies/testing.md` and `troubleshooting.md` say what to do about each:
  - an instance that never acknowledges is an old version (finish the upgrade) or one that cannot read the list (its log and `OBS-8`'s meter);
  - samples that do not come mean the window's documents have no traffic.

**Condition 4, the downgrade and the lost key, as rules (`CTL-47`):**
- an absent key means an instance reads candidates by key;
- a present list no longer maintained can only cost samples, never produce a pass: a candidate staged by a control plane that does not maintain the list is unlisted, so no instance samples it, and the old control plane's rule closes a window without samples as insufficient.
- **Test:** the stale-list case.
