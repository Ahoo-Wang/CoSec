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

### The Nexus publish plugin's marker, three times (2026-11-20)

The plugin `io.github.gradle-nexus.publish-plugin` 2.0.0 exists on the plugin portal and not on Maven Central. Three jobs
failed on it in all six attempts, about 12 minutes of them: CodeQL run 44765167001 (15:37 UTC), CodeQL run 44827683777
(22:17 UTC, head `ef06aeb83`; its re-run passed) and the `ctl-2` shard of mutation run 44835108385 (23:13 UTC). Each log says
`Plugin [id: 'io.github.gradle-nexus.publish-plugin', version: '2.0.0'] was not found in any of the following sources`, with
`MavenRepo` and `Gradle Central Plugin Repository` as the repositories searched.

The third one printed what the repositories answered, for the first time (`diagnose`, from the same runner, after each
attempt, six attempts, one edge: Cloudflare PDX): **the plugin portal answered `200` with the marker, and Maven Central
answered `429`**, every time. Central is throttling that runner, for at least 12 minutes, and Gradle reports a `429` from
the first repository as "not found in any of the following sources" without taking the marker from the portal, which has it.
(An earlier version of this section read the message as a `404` from the portal: wrong.) So the earlier failures of this
shape, which `diagnose` could not see, are likely the same: Central throttles a runner's address, and what a job loses is
what that answer hides. The kind of artifact decides what can help:

- **A file only the portal has** (this marker, the JMH plugin's): ask the portal first or only, so that Central's answer
  does not matter. In `pluginManagement` that is `exclusiveContent` on the portal for the groups of those plugins.
  `settings.gradle.kts` is where the build says where it resolves from, so that change is the maintainer's to decide
  (`AGENTS.md`); it is the one that removes this failure.
- **A file Central has** (the BOMs of the Kotlin and Dokka plugins, `jmh-core`, #1771): a second host for Central's files,
  which is off, below.

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
