# CI and release gate efficiency: what the minutes are spent on

Measured on 2026-11-22, after `8.0.0-beta.6`, from the GitHub runs named below. Every number comes from
`gh run view <id> --json jobs,createdAt` or `gh pr list --json`, read with
[`scripts/ci/run_timings.py`](../../../scripts/ci/run_timings.py) (`runs`, `summary`, `merges`); nothing is estimated
unless a line says so. "Wall" is from the run's creation to the end of its gate job; "runner-hours" add the jobs' own
durations. Seconds are medians unless marked p90. Background: the earlier measurement of 2026-11-19/20 is
`ci-affected-groups-proposal.md` (removed since; `git show 8b881ed3:docs/reviews/2026-11/ci-affected-groups-proposal.md`); since then the path rule (`plan.py`) and the CodeQL
skip (`codeql_needed.py`) shipped, so the numbers below are what is left.

## 1. A pull request

**`ci.yml`, the 40 newest green `pull_request` runs** (45045538352 back to 44992599647, 2026-11-21 10:00 to 2026-11-22
16:45 UTC):

| | median | p90 |
|---|---|---|
| wall to `CI` | **6.0 min** (362 s) | **15.8 min** (949 s), max 30.8 |
| `Test host` (`spring-boot`): job / its `Check` step | 278 / 230 s | 334 / 297 |
| `Test core`: job / `Check` | 253 / 220 s | 339 / 309 |
| `Test test`: job / `Check` | 217 / 165 s | 272 / 230 |
| `Test backends` / `extensions` / `migration` / `platform` / `edge` / `api` | 179 / 144 / 122 / 120 / 98 / 54 s | |
| TypeScript against a real host / Node 22 / Node 24 | 106 / 83 / 83 s | |
| Docs, Kubernetes manifests, Plan, actionlint, Codecov notify, CI | 46, 12, 8, 9, 9, 3 s | |

Inside a `Test` job: setup and checkout about 15 s, **`Resolve the dependencies` 15 to 18 s** (the dependency cache
works: it was 1.2 min in CodeQL before #1784), the `Check` step is the rest. The `Check` step is compile + tests +
detekt in one Gradle call; the job log does not split it further, and splitting it would need a build scan.

- **The critical path** is `Test host` in 23 of 40 runs, `Test core` in 7, `Test test` in 6. The `CI` job ends 6 s
  after the last `Test` job.
- **The spread is queueing, not work.** The latest start of a `Test` job after the run's creation is 66 s median, 840 s
  p90, 1712 s max. 12 of the 40 runs waited more than 5 minutes for a runner, and their walls are the 12 longest
  (607 to 1846 s). Each of them was created while a manual dispatch was running: a mutation run (45019453071 for
  45019441498 and 45022302300; 45027510164 and 45028318726 for 45027925173 and 45028312224), JMH A/B runs
  (45001702282 … 45005209612 for the runs from 08:15 to 09:18), system test dispatches beside them. Without the wait
  the run is the longest `Test` job plus about 15 s: 3.5 to 5.5 minutes.
- **Groups run per PR run:** 9 of 9 in 9 runs, 8 in 11, 7 in 4, 5 in 4, 4 in 2, 3 in 8, 1 in 2. A run of all nine
  (a build or CI change) has a median wall of 509 s.

**What a docs-only change runs:** `docs/roadmap.md` alone (45019232091, 44999302629): Plan, Docs, actionlint,
Kubernetes manifests, the three TypeScript jobs and `Test test` (always: `conformance` and `architecture`): 195 to
269 s wall, `Test test` deciding. A root `*.md` adds `core` and `host` (`ROOT_DOCUMENT_READERS`): 228 to 281 s
(45045538352, 45035480115). CodeQL's Java/Kotlin job skips: 18 s median over 5 skipped runs.

**What a CI-only change runs:** every group (any `.github/` or `scripts/ci/` path), so the nine-group wall above
(509 s median); CodeQL analyses only when `codeql.yml`, `.github/codeql/` or a build file changed. A path under
`platform/**`, `k8s/**` or `test/system-*/**` adds the system test (section 3.3).

**CodeQL** (`codeql.yml`, 30 newest green PR runs, 45045538361 back to 45001920342): `Analyze (java-kotlin)` analysed
in 25: **667 s median wall, 795 s p90**; inside it `Build` 387 s, `Perform CodeQL Analysis` 122 s, `Resolve the
dependencies` 51 s, `Initialize CodeQL` 17 s. **For a code PR the required check that decides is still CodeQL,
11.1 min against `CI`'s 6.0.**

## 2. The cost of a merge under strict protection

The 40 newest merged PRs (#1826 to #1869): from the PR's last commit to its merge, **10.9 min median, 18.0 p90**. In 20
of the 40 the last commit is the update with `main`; for those the wait is 11.1 min median, which is
`max(CI, CodeQL)` plus the bot's latency: CodeQL's 11.1 min is the bound. The other 20 (last commit the author's own,
already current) waited 8.0 min.

## 3. The three gates

### 3.1 The full mutation run

Run **45037155842** (`max-parallel: 16`, branch since deleted), 2026-11-22 15:13 to 16:29: **76 min wall, 68 shards
green, 16.1 runner-hours**, 794 mutants. (Run 45029659912, cancelled, `max-parallel: 8`, is the background's.) Per shard:

| step | median | sum over 68 shards |
|---|---|---|
| Set up Java and Gradle | 8 s | 10 min |
| Restore the dependency cache | 5 s | 7 min |
| Resolve the dependencies | 44 s | 49 min |
| Warm (`./gradlew testClasses`) | **41 s** | 49 min |
| Check the mutants | **734 s** (max 1343, `sta-2`) | **14.0 h** |

**64 s a mutant** median (p90 80, min 39, max 102), from each shard's `Check` step over its mutant count. The check is
87 % of the run's runner time; resolving and warming together are 10 % (1.6 h). The warm step took 125 s in the
background's cancelled run, 41 s here: both read the same Gradle cache, so it varies with what that cache already holds.

At 8 at once the same 16.1 runner-hours take about 2 h (the background's run); at 16, 76 min, of which the last wave is
the slowest shards (`sta-2` 22 min, `snp-6`, `ctl-8`, `ctl-7`, `ctl-11` 19 min each).

### 3.2 The 1M property stress

Run **45036650044**, 2026-11-22 15:08 to 16:00: **52 min wall**, decided by one job:

| job | job | of which `Run property stress` |
|---|---|---|
| `:runtime` | **3080 s** | 3037 s |
| `:engine` | 1178 s | 1119 |
| `:model` | 843 s | 792 |
| `:migrate`, `:control`, `:document`, `:gateway`, `:jwt` | 429, 336, 283, 170, 124 s | |

Inside `:runtime` (its uploaded test reports): `SnapshotInstallOrderPropertyTest` 1197 s + 874 s (two tests),
`SnapshotSourceModelPropertyTest` 429 s + 415 s (two tests); the other 7 tests 35 s together. Inside `:engine`,
`IndexPropertyTest` holds 1045 of 1078 s, its longest test 489 s. The tests of a module run one after another in one JVM:
the runtime job is the sum of four tests, the longest of which is 20 minutes.

### 3.3 The system test

Run **45036654015** (dispatch) and **45035427583** (PR): **8.3 min wall**, the `control-host` leg deciding (492 and
496 s: building the distributions 49 to 72 s, the gateway image 51 to 57 s, the control-plane host image 37 s, starting
the stack 61 to 64 s, the scenarios 193 s). The `embedded` leg takes 195 to 254 s, Kubernetes on kind 275 to 294 s.

## 4. The changes, ranked

Ranked by wall time saved where someone waits (a release gate, a PR in the queue). "Constraint" names the line of the
brief each one touches.

| # | change | saves | risks | constraint |
|---|---|---|---|---|
| 1 | **Mutation: one worktree and one daemon per shard, the patch reverted between mutants; one Gradle call per mutant** (Part 2.2) | of 64 s a mutant, the two cold Gradle starts and the worktree are the part that does not depend on the mutant: if they are half (to be measured on a subset before anything is changed), **about 7 runner-hours and 30 to 35 min of the 76 at 16 at once** | a daemon or a tree that carries state from one mutant to the next decides the next verdict | "nothing of one mutant may decide the next": proved in `MutationCheckTest` (a kill's result files, a tree after `DOES_NOT_COMPILE`, a revert that fails stops the shard) |
| 2 | **Property stress: split a module's job by test class** (or by test for the four long runtime tests) | wall 52 min → about **20 min** (the longest single test, 1197 s, plus a minute of setup); runner time +1 min per extra job | none to what is proved: the same tests with the same iterations; more jobs at once on the account's runners for 20 minutes | none; it is how the matrix is cut |
| 3 | **Mutation: `max-parallel` as an input** (Part 2.1) | at 16: 2 h → **76 min**, no runner-hours saved | while it runs, PRs wait for runners: the 12 slow PR runs of section 1 (up to +28 min) each overlapped a dispatch, 8 shards wide at most. At 16 the PR queue stalls for the hour; to be used at a release gate, not routinely | concurrency rules of the file kept; the weekly schedule stays at 8 |
| 4 | **Keep manual dispatches out of the PR queue's way**: run JMH A/B and full mutation runs when no PR is armed, or ask for fewer shards | p90 of a PR's wall 15.8 → about 6 min (the median), and with it the merge wait's tail (18 min p90) | none to what is proved; a dispatch waits | a working rule, not a change to a file |
| 5 | **CodeQL `Build` without `testClasses`** (lever C of the earlier proposal) | bounds the merge: 387 s of CodeQL's 667 is `Build`; unmeasured share for test sources. Would move the merge wait toward `CI`'s 6 min only if it takes CodeQL under it | CodeQL stops analysing test code: a decision about what CodeQL is for | the required check stays; for the maintainer |
| 6 | Split `Test host` / `Test core` (`ci.yml` grouping) | `Test host` 278 s is the critical path in 23 of 40 runs; splitting it saves nothing while CodeQL (667 s) decides the merge | none | `ci.yml` grouping: not in scope |
| 7 | Warm once, shared between shards (Part 2.3) | resolving and warming are 85 s of a shard's 830, 1.6 of 16.1 runner-hours; a shared warm still has each shard restore it, so at most about 1 h of runner time and 1 to 2 min of wall | another cache written by a gate run, and its restore on 68 shards; Maven Central sees no difference (the dependency cache already serves the downloads) | **not worth building**: the numbers do not pay for a new cache |
| 8 | Select mutants on a PR from its changed paths | nothing on the gates (the full run stays); would add a job to PRs | a rule whose code moved and whose mutants were not selected | **not proposed**: the gates are where the time is |
| 9 | Narrow the `Tests:` headers | depends on the patch: the `Check` step is mostly Gradle's fixed cost (item 1) until that is removed | a header narrower than the guarding tests misses a kill and reports a guarded rule as not guarded | measure after item 1 |

**Order I recommend:** 3 (one input, one PR, ready for the next gate), 1 (the largest runner-time saving, measured
first), 2 (the stress run's wall, with no risk), then 4 as a working rule. 5 needs the maintainer's word; 6 to 9 wait.

**Limits.** The 40 CI runs are those of a release day: more dispatches than usual, so the queueing tail is worse than on
an ordinary day. The merge wait counts from the last commit's date, which for a sync is the time the bot pushed it. The
per-mutant time divides a shard's `Check` step by its count, so it includes Gradle's start-up, which item 1 is about.
