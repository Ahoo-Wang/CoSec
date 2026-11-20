# CI scripts

## `resolve-dependencies.sh`

Resolves a checkout's dependencies, retried, before a job builds or tests anything, so that a download that fails (a `429`
or a wrong `404` from Maven Central or the plugin portal) or a restored Gradle cache that is wrong fails this step, not the
build, and a retry never repeats work that could change a result. The workflows that run Gradle call it before their first
Gradle command: `ci.yml` (each test group resolves only its own projects, `RESOLVE_TASKS`), `codeql.yml`, `mutation.yml`,
`property-stress.yml`, `jmh-ab.yml`, `jmh-v7-v8.yml`, `system-test.yml`, `docker-deploy.yml` and `package-deploy.yml`. Its
header says how the attempts, the pauses and the removal of `caches/modules-2` work, and what each run's log shows:

- `Dependencies resolved on attempt N of 6` (a warning): a retry healed a failure. On most runs, the repositories are
  throttling CI routinely.
- `A restored Gradle cache names a file it does not hold`: the stale-index mode below, healed at once.
- `Dependencies did not resolve in 6 attempts` (an error): about 15 minutes of asking did not help; the `Could not` lines
  above name the repository and the answer, and a re-run takes another runner.

A `Could not find` or `Could not GET` in the build step after a green resolve step means the build asked for something the
resolution left out (the configuration pattern of `.github/gradle/resolve-dependencies.init.gradle.kts`).

### The dependency cache

Maven Central answers `429` to a runner that asks for too much, and a `429` fails the job (below). The cure is to ask it
less. What one push asks of it, counted from the logs of completed runs (2026-11-20):

| Workflow | Jobs that download | Gradle cache restored | Hit |
|---|---|---|---|
| `ci.yml` | 8 test groups, `typescript-host` | the last main run's manifest of the same job (restore-key), and its `gradle-dependencies` entry | yes, all nine (runs 44837144050 on a pull request, 44835207905 on main) |
| `system-test.yml`, `docker-deploy.yml` (AOT smoke), `property-stress.yml` | 2, 1, 1 | the same | yes |
| `codeql.yml` | 1, every push | none: off since #1490 | cold, the whole build every time |
| `jmh-ab.yml`, `jmh-v7-v8.yml` | 1 each, manual | none: off since #1490 | cold |
| `mutation.yml` | up to 49 shards at once, a dozen for a subset | the manifest of the last run of this workflow **on main**, which is the weekly one | yes, but up to a week old: a version bumped since is downloaded by every shard at once |

So CodeQL asks for the whole build on every push, and a mutation run asks for the difference since last Sunday 12 to 49
times at the same moment, which is what failed on 2026-11-20 (#1733 counted 36 shards asking at once).

`dependency-cache.yml` writes one cache, `gradle-dependencies-v1-<repos>-<versions>`, from nothing, after a resolution of
every configuration of the build that succeeded (an entry becomes visible only when it is complete, and a job that failed
saves nothing: no reader finds a half-written one). It runs on main when a version, a repository or the Gradle
distribution changes (`.github/scripts/dependency-cache-key.sh`), when this script changes, and weekly, because GitHub
evicts what is not used for seven days. `.github/actions/dependency-cache` restores it, read-only, in `codeql.yml`,
`mutation.yml`, `jmh-ab.yml` and `jmh-v7-v8.yml`; the jobs that restore a Gradle cache of their own and hit it are left as
they are.

- `<repos>` is a hash of `settings.gradle.kts` and of every line of a build script that names a repository, a plugin source
  or `pluginManagement`; `<versions>` a hash of `gradle/libs.versions.toml` and the wrapper properties. A restore falls back
  from a missing `<versions>` to the newest entry with the same `<repos>`, never across a change of `<repos>`: Gradle's
  metadata cache is kept per repository, and what #1730 changed (where a plugin is read from) left every cache that
  remembered the old answer failing on `Could not find`. The script's removal of `caches/modules-2` on that error stays.
- In `mutation.yml` it is restored after the job's Gradle cache, so that its newer index is the one that stays; the older
  files that cache also holds are only unused.
- A manual run of `dependency-cache.yml` on a branch writes a cache that branch alone reads: how a change of the key, the
  paths or the workflow is tried before it is on main.

What it does not solve: the first run after a version bump downloads everything, once, in `dependency-cache.yml`, and the
jobs that run before it is written (the pull request that bumps the version, which cannot write) download the difference
each. A repository that answers `429` for a file this cache does not hold still fails the job.

`resolve-dependencies.sh` also asks less while it is throttled: after an attempt, when a searched URL answers `429`, the next
attempt neither refreshes every module the Gradle home holds (`--refresh-dependencies`) nor removes `caches/modules-2`, and a
`Retry-After` the repository sends is waited for, up to `RESOLVE_MAX_RETRY_AFTER` seconds (default 300), in place of the fixed
pause. Central's `429` of 2026-11-20 sent none.

### Why the fallback host does not answer a 429

A fallback repository placed last is asked only when the repositories before it said "not found". A `429` is not "not found":
Gradle treats it as a repository that failed and does not go on to the next. Mutation run 44835108385 (`ctl-2` shard, six
attempts, 12 minutes) is the proof: the plugin portal answered `200` with the marker, Maven Central answered `429`, and
Gradle reported "not found in any of the following sources" without taking it from the portal. A second host last in the
order would not have been asked either, so the fallback (off, below) does nothing for a `429`; asking Central less does.

### The fallback repository

A `Could not find` for a file that exists is a 404 from the repository, and no pause helps one that lasts. On 2026-11-20 it
failed all six attempts, over 12 minutes, four times: CodeQL on two pull requests and two shards of one mutation run, for
POMs Central serves (the BOMs of the Kotlin and Dokka plugins, `jmh-core`, the OpenTelemetry instrumentation BOM), from Central
and from the plugin portal, while the other shards of the same run passed. A cache was not the cause: attempt 3 had removed
`caches/modules-2` and failed the same way. The log of a failed attempt never says the HTTP status, so after one the script
asks each URL Gradle searched itself and prints the status and the CDN's headers (`diagnose`): read that to tell a 404 from a
429 or a block.

`RESOLVE_CENTRAL_FALLBACK=on` installs `.github/gradle/central-fallback.init.gradle.kts` into the job's Gradle home, which
appends Google's read-only copy of Maven Central as the last repository, for plugins and for projects. Gradle asks it only for
what the repositories before it answered "not found" for, so a healthy run never reads from it, and an artifact no repository
has still fails the job. It would be installed for the whole job because Gradle keeps its module cache per repository: the
build steps find what the resolving step downloaded from it only if they ask the same repositories.

**It is off: no workflow sets it.** Where the build resolves from (`AGENTS.md`: Maven Central, then the plugin portal for
plugins) is the maintainer's decision, and a second host that no checksum verifies would widen what CI tests against, even
if a release never used it. Turning it on is the maintainer's call: add `RESOLVE_CENTRAL_FALLBACK=on` to the resolve step of
the workflows that test, measure or analyse, never to `package-deploy.yml` or `docker-deploy.yml`, which publish what they
build. Until then the next failure prints, after each attempt, the HTTP status and the CDN's headers for every URL Gradle
searched (`diagnose`), which is what that decision needs.

What still fails, and why: a file only the plugin portal hosts (the marker and jar of the Nexus publish plugin
`io.github.gradle-nexus.publish-plugin`, and the JMH plugin's) has no second host. The portal answered `Plugin ... was not found`
for the Nexus marker for 12 minutes in CodeQL's run 44765167001, and that failure is only retried, as before.

### A change of repositories invalidates what restored caches say

Gradle's cache remembers, per repository, which modules and files it found and did not find. Changing which repository an
artifact is read from (a `repositories` block, `pluginManagement`) makes a restored cache answer from what it remembers about
the old one. On 2026-11-20, #1730 put Maven Central ahead of the plugin portal for plugins; the required check's `Test host`
job, whose restored cache held the old answers, failed on `Could not find dokka-gradle-plugin-2.2.0.jar` (and two more jars,
all of which Central serves), from Gradle's cache lookup and not from a repository, on `main` and on every pull request until
its cache entries were removed. #1730 had been tried only where no cache is restored (the jmh A/B with its cache off, the
system test, CodeQL), and the workflow with a restored cache is the required check. **A change of repositories is tried
against the caches that exist, and comes with the removal of the caches that remember the old answer or with this step**,
which removes `caches/modules-2` the moment it sees that error, without the pauses and attempts that cannot help.
