# AGENTS.md: TypeScript packages

These rules apply under `typescript/`. The root `AGENTS.md` still governs Kotlin, Gradle, the wiki and everything else.

CoSec's TypeScript packages are the client of a CoSec application and its React hooks. They move here from the
[fetcher](https://github.com/Ahoo-Wang/fetcher) repository (`@ahoo-wang/fetcher-cosec` and the CoSec hooks of
`@ahoo-wang/fetcher-react`); this directory is the workspace they land in. What a client may rely on is
[`wiki/integration/clients.md`](../wiki/integration/clients.md), and CoSec owns it.

## Packages

| Directory        | Package                    | Published | Built on                                                             |
| ---------------- | -------------------------- | --------- | -------------------------------------------------------------------- |
| `cosec-fetcher/` | `@ahoo-wang/cosec-fetcher` | yes       | `@ahoo-wang/fetcher` and its eventbus and storage packages, from npm |
| `cosec-react/`   | `@ahoo-wang/cosec-react`   | yes       | `cosec-fetcher` and React                                            |

A package is added here, to the workspace's members (`cosec-*`), and to `TypeScriptWorkspaceTest` in `:architecture`.

## Workspace

A pnpm workspace of its own, apart from `wiki/` (whose lockfile and toolchain stay as they are):

| File                                                    | Role                                                                     |
| ------------------------------------------------------- | ------------------------------------------------------------------------ |
| `package.json`                                          | Private root: scripts and shared dev tooling only; pins `packageManager` |
| `pnpm-workspace.yaml`                                   | Members, the dependency catalog, `allowBuilds`, `minimumReleaseAge`      |
| `pnpm-lock.yaml`                                        | The only JavaScript lockfile of this workspace                           |
| `tsconfig.base.json`, `eslint.config.js`, `.prettierrc` | What the packages extend or follow                                       |

```bash
cd typescript
pnpm install              # CI runs pnpm install --frozen-lockfile
pnpm build                # packages import each other through dist
pnpm lint                 # eslint --max-warnings 0, then inside each package
pnpm typecheck
pnpm test
```

- Add an external dependency to the `catalog` in `pnpm-workspace.yaml` and reference it with `catalog:`. Packages of this
  workspace reference each other with `workspace:`.
- Never add another lockfile, a nested `pnpm-workspace.yaml` or `shamefully-hoist`. A package declares what it uses.
- A dependency's install script runs only when `allowBuilds` names it. A version npm published less than a day ago is
  not installed (`minimumReleaseAge`).

## Dependency direction

- CoSec's packages depend on fetcher's, never the other way. Every `@ahoo-wang/fetcher*` package comes from npm, through
  the catalog, as a peer dependency of the published packages. Nothing here depends on `@ahoo-wang/fetcher-cosec`, the
  name these packages replace.
- Packages of this workspace depend on each other as peers (`workspace:~`, which becomes `~x.y.z` on publish).

## Versions

- One version source: `version` in the root `gradle.properties`. The packages take it; `TypeScriptWorkspaceTest` fails
  when a `package.json` differs. Maven and npm release together under one tag.
- Peer ranges of the published packages are a contract: they are widened by hand or by Renovate, never narrowed.

## Code

- Strict TypeScript, ES modules, type-only imports (`consistent-type-imports`), `eslint --max-warnings 0`, the Apache 2.0
  header on every source file. The Kotlin structure rules (400 lines a file, one type a file) do not apply here.
- **A public API report per package** (`test/api/<package>.api.md`, api-extractor) is to a package what
  `api/<project>.api` is to a Kotlin module: a changed line is a public change, and a removed or changed one is a
  breaking change (`!` in the PR title). `pnpm test:api` checks it against the build; `pnpm test:api -u` rewrites it, and the change is committed on purpose.
- A package's unit tests may use its own model of CoSec (a fake server and a fake clock) for what needs a controlled
  clock or a controlled failure: a 30-second leeway and a lost response cannot be staged against a real host.
  **Whatever the model asserts about CoSec's answers is also asserted against the application of `test/client-host`**,
  and where the two disagree the host is right and the model is fixed. The bodies of `wiki/integration/clients.md` are
  the ones CoSec writes.

## Tests against a real host

`test/client-host` (a Gradle project, test sources only) is a real CoSec 8 application: the starter over WebFlux with
CoCache in memory (no Redis), one platform document, a login, the refresh and logout routes the starter serves
(`cosec.authentication.login.refresh.path`, `logout.path`: the answers of `wiki/integration/clients.md`), and `/test`
routes that count what reached it, echo the headers it received, end a session, sign in with an access token that has
already expired, take the revocations down until they are brought back up (the refresh answers `503`), and make the
refresh fail with an error (`500`). It listens on the loopback interface only (127.0.0.1: it signs in whoever names itself), and the trust
cases use `localhost` as the other origin. Never an example of a production host.

```bash
pnpm test:host     # builds the host with Gradle, starts it, runs every package's host tests, stops it
```

- The host tests of a package are `test-host/*.host.ts(x)` with a `vitest.host.config.ts`; what they ask of the host over
  plain HTTP (login, counters, the `/test` switches) is `typescript/test-host/hostApi.ts`, shared by relative import, so
  no package depends on the other's tests or exports it. They are apart from `pnpm test`: they
  need the JVM and Gradle. The `typescript-host` job of CI runs them on every pull request.
- `scripts/with-host.mjs` owns the host process: it prints `READY <port>`, the script sets `COSEC_HOST_URL` for the
  command, and the host stops when the script closes its standard input, whatever became of the run. On a shared machine,
  `GRADLE_ARGS="--no-daemon --max-workers=2" pnpm test:host`.
- **No case waits for a token, and none races its lifetime.** `/test/login-expired` issues a pair whose access token expired
  a minute ago (`TokenConverter.toToken` with explicit validities) and whose session and refresh token are live. A client
  that reads the expiry refreshes before it sends; a client whose clock is behind (the test sets only `Date` back:
  `vi.useFakeTimers({ toFake: ['Date'] })`) sends it and is refused `401 token-expired`. Which path a case took is asserted
  from the wire (`recordWire`: `<path> <status>` in order) and the host's counters, never from timing, and there is no
  `earlyPeriod` trick.
- Add a case here when a package's unit test asserts what CoSec answers; `:client-host:check` keeps the host itself
  answering as the tests assume.

## Releasing

Publishing the GitHub release `vX.Y.Z` publishes the packages to npm, from the `npm-publish` job of
`.github/workflows/package-deploy.yml`, in this order:

1. The Node and npm it runs on are printed and asserted (Node 22.14 and npm 11.5.1 or later: trusted publishing).
2. The release tag, `gradle.properties` and every `package.json` must carry one version, or the job fails, printing all of
   them, before anything is built (`node scripts/release.mjs check <tag>`).
3. Frozen install, build, tests; `pnpm pack` writes the tarballs (`workspace:` and `catalog:` become versions; the
   publish refuses a package.json that still has one), `scripts/package-check.mjs --tarballs` checks them, and **those
   tarballs** are published: `@ahoo-wang/cosec-fetcher`, then `@ahoo-wang/cosec-react`.
4. The dist-tag follows the version: `-alpha.n` is `alpha`, `-beta.n` is `beta`, `-RCn` is `rc`, none is `latest`. A
   pre-release never gets `latest`, and a pre-release this table does not know fails. Install one with
   `pnpm add @ahoo-wang/cosec-fetcher@beta`.
5. A version already on npm is skipped with a message, so re-running the job after a partial failure goes on with the
   next package. "Already on npm" needs positive evidence: `npm view <name>@<version> version` printing exactly that
   version; an `E404` means "not published", and anything else (an error, exit 0 with other or no output) fails the job.
   After each package the log shows `npm view <name> dist-tags` (not asserted: the registry can lag), so the run records
   the tag the version got and that `latest` did not move.

There is no npm token: each package has a **trusted publisher** on npm, bound to this repository, to the file name
`package-deploy.yml` and to the environment `npm-publish`, and npm writes the provenance. Renaming the file or the
environment means changing them on npm first. A trusted publisher that has not published within two days of its creation
lapses: the maintainer creates it again just before the first release. `repository.url` of each package is exactly
`git+https://github.com/Ahoo-Wang/CoSec.git` (the repository of the workflow, with GitHub's own case) and `directory` is
`typescript/<package>`: `TypeScriptWorkspaceTest` holds both, and the publish checks the url against `GITHUB_REPOSITORY`.

Every pull request rehearses it: the `typescript` job of `ci.yml` packs both packages, checks the tarballs and runs
`npm publish --dry-run` on them with the dist-tag and in the order a release would use (`scripts/release.mjs`), so the
first real publish is not the first time the command runs.

## Supply chain

The CI job has `contents: read`, no secrets, a frozen lockfile and actions pinned by commit SHA. Nothing is published but
by a release the maintainer creates, in a job with the `npm-publish` environment and OIDC (no npm token), from the
tarballs that job packed and checked itself ([Releasing](#releasing)).
