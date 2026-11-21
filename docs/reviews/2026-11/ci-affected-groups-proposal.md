# Proposal: make the run after a sync cost what the change can affect

Written 2026-11-21 by the session that did #1771, #1781 and #1784. A proposal: nothing is built. Strict `main`, the `CI`
gate and the required checks stay as they are. Numbers are from the GitHub runs and pull requests of **2026-11-19 and 20
(UTC)**, measured with the scripts I kept in the scratchpad (`analyze.py` and friends); each number says what it counts.

## 0. The finding that changes the question

**The required check that bounds the queue is not `CI`, it is CodeQL's `Analyze (java-kotlin)`.**

| | median wall-clock | p90 | what it is |
|---|---|---|---|
| `ci.yml`, successful PR runs (n = 301) | **4.7 min** | 6.6 | the longest of its jobs: `Test core` 3.9, `Test host` 3.5, `Test test` 3.1 |
| `codeql.yml`, successful PR runs (n = 408) | **8.9 min** | 11.7 (max 19.4) | `Build` 5.6 (traced, `clean classes testClasses`, no cache by design), resolve 1.2, analysis 1.1, init 0.3 |
| gap between two consecutive merges on `main` (n = 118) | **12.4 min** (p25 10.6, p90 55) | | 52 of 118 gaps are under 12 min; at most 5 merges in an hour |

When the queue is saturated (p25 of the gaps, 10.6 min) one PR merges per CodeQL run plus the bot's latency (about 1.7 min:
the push, `update-armed-prs`, the merge commit, the workflow start). **Shortening `ci.yml` alone does not move the cap**,
whatever it skips: the deciding run is `max(CI, CodeQL)` and CodeQL is about twice CI. Everything below counts both.

## 1. Measurements

**The window:** 119 PRs merged (55 on the 19th, 64 on the 20th); 507 `ci.yml` runs on their branches (301 green, 141
cancelled by a newer push, 65 failed), 11,297 job-minutes; 2.5 green runs per merged PR; **97 of 119 PRs had a green run
after a different green sha** (a sync or a push). The 182 consecutive green-to-green pairs split into 133 where `main` was
merged in (median 40 files changed since the last green, max 291) and 49 PR's own pushes (median 2 files).

**What the PRs touched** (paths of the merged PR, up to GitHub's 100-file listing):

| Category | PRs | share of the 507 runs' job-minutes | deciding `ci.yml` run wall (sum) |
|---|---|---|---|
| `docs/`, `wiki/`, `*.md` only | 27 (23 %) | 10.7 % | 115 min |
| `.github/`, scripts, root build files, `gradle/` (the "build" class: runs everything) | 27 (23 %) | 17.6 % | 138 min |
| `docs` + `typescript/` or `k8s/` only | 1 | 0.4 % | 4 min |
| module code | 64 (54 %) | 71.4 % | 341 min |
| *only test sources*, any module | 3 (1 in one module) | | not worth a rule |

**Per job, successful PR runs** (median / p90, minutes): Test core 3.9 / 5.5, host 3.5 / 4.4, test 3.1 / 4.1, backends 2.2 /
3.1, extensions 1.9 / 2.6, TypeScript against a real host 1.8 / 2.4, migration 1.6 / 2.3, TypeScript Node ×2 1.5, edge 1.3 /
1.9, Docs 0.8, api 0.7; Plan, k8s, actionlint, TypeScript coverage, CI each 0.1 to 0.2. The tail of the critical path is
three jobs; the rest is parallel slack.

**Where the minutes went:** green runs 7,455 job-min (avg 25), cancelled 2,201 (141 runs, avg 16), failed 1,642 (65, avg
25). Of the failed runs the `CI` job is red in all, `Test test` in 36, `Test core` 20, `Test host` 18, `Test backends` 16.

**Module-code PRs by the number of the 8 `Test` groups the rule below would run:** 1 group: 3 PRs, 2: 4, 3: 6, 4: 1, 6: 14,
7: 32, 8: 4. Fifty of 64 run six or more: a change in `model`, `engine`, `spi`, `document` or `runtime` reaches almost
everything, and `spring-boot` depends on sixteen modules.

## 2. The rule (what `plan.py` would decide)

**Inputs.** The list of changed paths (`git diff --name-only origin/<base>...HEAD` in the Plan job, `fetch-depth` enough),
`settings.gradle.kts` (the groups), and the **project dependency graph**. The graph is not a hand-kept table: `plan.py`
reads it from the `project(":x")` references of each module's `build.gradle.kts` (the `declared` half of what
`architectureFacts` writes; the Plan job has no JDK and today finishes in 6 s, and running Gradle there costs about 40 s,
which is why I read the declarations rather than the task's output). A guard in `test/architecture` (next to
`ProjectListTest`, which already runs `plan.py`) compares `plan.py`'s graph to the `facts.txt` of every project, so the
two cannot drift.

**The rule, in order, first match wins:**

1. **`push` to `main`, `7.x`, `release/**`, `workflow_dispatch`, `schedule`: every group runs.** (No change.)
2. **A change to the build itself runs everything:** `build.gradle.kts` (root), `settings.gradle.kts`, `gradle/**`,
   `buildSrc/**`, `gradle.properties`, `config/**`, `codecov.yml`, `scripts/ci/**`, `.github/**`. Unmapped paths outside a
   module (anything not in rows 3 to 6) also run everything: the safe direction is the default.
3. **A module's group runs when the PR touches a module of it or any module it depends on, transitively** (the reverse
   closure of the changed modules over the graph; a change in `runtime` reaches `control`, `cocache`, `spring-boot`,
   `migrate`, the edge and extension modules, the test projects).
4. **The `test` group always runs when any main source, rule, mutant, scenario or build file changes** (your condition for
   `conformance` and `architecture`: the first reads every module's tests and applies every mutant patch, the second reads
   every module's dependencies and sources). To make that cheaper, `conformance` and `architecture` could leave the
   `test` group for a job of their own that always runs, so that `e2e`, `client-host` and `system-*` follow the closure
   like any other module; I have not measured how long the pair takes alone.
5. **Non-module paths:** `wiki/examples/**` runs the `test` group (`DocumentationExamplesTest`); `schema/**` runs
   `document` (+ `test`); `contract/**` runs `runtime` and `jwt` and the two TypeScript jobs; `typescript/**` runs the
   TypeScript jobs; `k8s/**` runs `Kubernetes manifests`. `docs/`, `wiki/`, `skills/`, `*.md`, `llms.txt` run no
   Gradle group. `Plan`, `actionlint`, `Docs`, `Kubernetes manifests` are 0.1 to 0.8 min each, and always run (they are
   not on the critical path; skipping them saves nothing worth a rule).
6. **The TypeScript jobs** run when `typescript/**` or `contract/**` changes, or when a group they build on is in the set
   (`TypeScript against a real host` builds `client-host`, so it follows `spring-boot`/`client-host`'s closure).

**How a skip is reported, never silently.** `plan.py` prints, beside `matrix`, a `skipped` list: for each group the reason
("no module of `edge` or its dependencies changed", "only `docs/` changed"). The `plan` job writes it to the step summary
and as one `::notice::` per skipped group; the `CI` gate prints it too. The gate's rule stays "every job passed", with one
precise exception: a `test` matrix that is empty **because `plan` said so** (`needs.plan.result == success` and the
plan's `matrix` has no jobs) is accepted; a `test` job skipped because `plan` failed, or cancelled, still fails the gate.
The `CI` job stays the one required check, with the same name.

**What must be fixed before it can ship (a real cost, not a footnote): Codecov.** `codecov.yml` waits for exactly 8 uploads
(`after_n_builds`, held by `plan.py`'s count check). A skipped group uploads nothing, so `codecov/patch` and
`codecov/project` would wait forever (they are not required checks, but this repository merges on them being green). Two
fixes, both needing a try on a pull request: per-group flags with `carryforward: true`, and `after_n_builds` replaced by
`wait_for_ci`, or a small job that uploads the carried-forward report of the skipped groups. I would build this part first and
show a PR that skips groups with both Codecov statuses green.

**On `main` after the merge:** the push run is the full one (rule 1), as today, and is what notices a wrong skip. A wrong
skip is a regression no group of the PR's closure saw; the full run reports it red within five minutes of the merge, and
the response is the existing one for a red `main`: a revert PR (this repository already works that way), plus the rule fix.
Two things make a wrong skip unlikely: the graph is the build's own, and the default for anything unmapped is everything.
Two things make it possible: a runtime coupling that is not a Gradle dependency (the Spring `AutoConfiguration.imports`
discovery, a resource read by classpath scan, `ServiceLoader`; the starter depends on the modules it wires only
`compileOnly`, which `project(":x")` parsing sees but a `runtimeOnly` would not), and `plan.py` bugs. I would add a
property test of the planner: for each of the last N merged PRs' file lists the planner picks a superset of the groups that
failed in any red run on `main` since 2026-11-01 (a regression test against history).

## 3. What it would have saved, and what it cannot

Applied to the 2 days (every complete run of the 119 PRs, CI only, the PR's final file list for all its runs):

| | job-minutes | wall of a run | the run that decides the merge, `ci.yml` alone |
|---|---|---|---|
| docs/wiki/md only (27 PRs) | **-94 %** | **-64 %** (4.1 -> about 1.5 min) | 115 -> 36 min summed |
| module code (64 PRs) | -22 % | **-1 %** | 341 -> 335 |
| build/workflow/script (27 PRs) | 0 | 0 | 138 -> 138 |
| **all** | **-26 %** (2,392 of 9,097) | -8 % | **598 -> 511 min (-15 %)** |

So the path rule is a **runner-minutes** saving (a quarter) and a modest queue-time saving (15 % of the CI deciding
time), not a throughput one. It saves nothing where it matters: **a PR in `model`, `engine`, `spi`, `document` or
`runtime` (the bulk of module-code PRs) runs six to eight of the eight groups, and the run is as long as the longest group**
(core 3.9 min); the bound of a deciding run, `max(CI, CodeQL)`, does not move for them.

**The same rule on the run after a sync**, measured on the 133 runs after `main` was merged in: `main`'s delta since the
last green run is not small (40 files median). Running a group only when *both* the PR's change and `main`'s delta reach its
inputs (the tree is then known green on one side) saves **20 % of those runs' job-minutes and 6 % of their wall-clock**,
and 86 of the 133 still run six or more of the eight groups. The reason is the same: the PRs that are in the queue are in
the core.

## 4. The levers that move the cap (CodeQL first)

**A. CodeQL does not need to run when no Java/Kotlin could have changed its result.** 36 of 119 PRs (30 %) touched nothing
CodeQL `java-kotlin` can see (no `*.kt`, `*.kts`, `*.java`, `*.gradle`, `gradle/`, `buildSrc/`, `.github/codeql/`,
`codeql.yml`, version catalog). For them the check runs 8.9 min and learns nothing. The required check must still report,
so the job runs and its steps are conditional (Initialize, Build, Analyze `if: needs-analysis`), reporting success with a
notice "skipped: no Kotlin, Java or build file changed". Over the window: about **36 x 8 min = 4.8 h of the 17.7 h of
CodeQL deciding time (-27 %)**, which is the saving that moves the cap, because it is the bound. Risk: low; the SARIF of
the PR's merge base stays the reference, and the full analysis still runs on `main` after every merge (CodeQL on `push`).
`CodeQL` (the code-scanning result check, shown `skipping` today) is unchanged.

**B. The dependency cache of #1784 takes CodeQL's resolve (1.2 min median) to a few seconds**: about -13 % of its wall for
every PR. Already in flight.

**C. CodeQL's `Build` (5.6 min) compiles `testClasses` too** (`clean classes testClasses -x detekt`). Dropping
`testClasses` would shorten it by an unmeasured share (tests are the larger half of the Kotlin in several modules) and
stops CodeQL from analysing test code. That is a decision about what CodeQL is for, not a CI one: I do not propose it
without your word; I would measure the share first (one dispatch).

**D. Split the critical path of `ci.yml`** (`plan.py` already has `SPLIT` for it): `core` into `runtime`, `control` and
the rest, and `host`. The median deciding run would go from 4.7 to about 3.5 min with no skipping and no risk to what is
checked. It helps only once CodeQL is under it (after A to C it is about 6 to 7 min for a code PR, so CI becomes the
smaller of the two, and D no longer matters; I would not do D first).

**E. Reuse by content (your point 4).** A group result is a function of the content of its inputs; a recorded pass keyed by
a hash of (the group, the content of its reverse-closure directories, the root build files) could be reused by the run
after the sync, and by a PR whose tree for that group equals `main`'s. Measured above (20 % of job-minutes, 6 % of wall on
the sync runs), it adds to rule 3 only what a path rule cannot say, at the price of a trusted store of results (a cache of
"passed" markers writable only by green runs on `main` and by the PR itself) and the standing risk that a recorded pass
hides a flaky test. **Not worth it**; the path rule plus A already take the cases that are cheap and safe.

**F. A cheaper run after a sync only for the "does it still merge" question.** What the 2026-11-20 re-run caught (mutant
patches that no longer apply, rule-registry collisions) lives in `conformance` and `architecture`. A sync run limited to
compile-everything + those two would take about 3 to 4 min instead of 5 to 9, but it would turn the required check into
a weaker one for the sync run (tests of the changed module not re-run on the merged tree), and `main`'s full push run
would then be the first to see a semantic conflict. **I recommend against it**: it is exactly the kind of change you ruled
out ("without weakening the required check").

**G. Update more than one PR per merge.** `update-armed-prs.yml` updates the oldest armed PR only, to avoid rerunning CI
for all (the file's own comment). The throughput question is whether the *next* PR could have its run in flight while the
current one's run finishes: it cannot under strict (it must contain the merged `main`), but the bot's latency (the
estimated 1.7 min per merge) is the one place to cut: update the next PR as soon as the current one is **armed and green
and about to merge** (the `workflow_run: completed` already triggers it; I have not read whether it waits for the merge
itself), instead of after it. That is a read-the-timestamps task first; I would not guess.

## 5. What I would build, in order, if you say go

1. **A** (CodeQL skip when nothing it sees changed): one workflow, low risk, the largest queue effect (-27 % of the bound).
2. **#1784** merged (B).
3. **The path rule** in `plan.py` with the dependency graph read from the build files and the architecture guard that holds
   it to `facts.txt`, the `skipped` report, the gate's one exception, **and the Codecov fix first**, shown on a PR with
   both statuses green. Saves 26 % of runner minutes and 15 % of CI's deciding time (more for docs, nothing for the core).
4. Measure C and G; decide on them.

**What none of this solves:** a PR in the core (about half of the module-code PRs) still runs nearly everything and
CodeQL's compile, so the queue's cap for the PRs that matter stays near 8 to 10 minutes per merge; the number of PRs a day
is bounded by that. The cap moves only with a different merge mechanism (a queue, which a user-owned repository lacks), or
by testing a batch of armed PRs together as one train and merging them in order, which changes what "the required check ran
on the PR" means. That is yours to decide; I have not designed it.

**Limits of these numbers.** The file lists are GitHub's (a PR with more than 100 files is cut; none of the docs-only ones
is). The durations are the runs of those two days, a busy period (runner contention is inside them). The saving for module
code assumes the final file list applies to every run of the PR. "Deciding run" is the last green `ci.yml` run before the
merge; the CodeQL deciding time is the sum of its median over the PRs, not a per-PR join.
