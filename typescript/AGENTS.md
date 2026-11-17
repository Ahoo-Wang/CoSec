# AGENTS.md: TypeScript packages

These rules apply under `typescript/`. The root `AGENTS.md` still governs Kotlin, Gradle, the wiki and everything else.

CoSec's TypeScript packages are the client of a CoSec application and its React hooks. They move here from the
[fetcher](https://github.com/Ahoo-Wang/fetcher) repository (`@ahoo-wang/fetcher-cosec` and the CoSec hooks of
`@ahoo-wang/fetcher-react`); this directory is the workspace they land in. What a client may rely on is
[`wiki/integration/clients.md`](../wiki/integration/clients.md), and CoSec owns it.

## Packages

| Directory | Package | Published | Built on |
|---|---|---|---|
| `cosec-fetcher/` | `@ahoo-wang/cosec-fetcher` | yes | `@ahoo-wang/fetcher` and its eventbus and storage packages, from npm |
| `cosec-react/` | `@ahoo-wang/cosec-react` | yes | `cosec-fetcher`, `@ahoo-wang/fetcher-react`, React |

A package is added here, to the workspace's members (`cosec-*`), and to `TypeScriptWorkspaceTest` in `:architecture`.

## Workspace

A pnpm workspace of its own, apart from `wiki/` (whose lockfile and toolchain stay as they are):

| File | Role |
|---|---|
| `package.json` | Private root: scripts and shared dev tooling only; pins `packageManager` |
| `pnpm-workspace.yaml` | Members, the dependency catalog, `allowBuilds`, `minimumReleaseAge` |
| `pnpm-lock.yaml` | The only JavaScript lockfile of this workspace |
| `tsconfig.base.json`, `eslint.config.js`, `.prettierrc` | What the packages extend or follow |

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
  breaking change (`!` in the PR title). The report arrives with the first package.
- A client test that needs a CoSec host runs against the application of `test/client-host`, never against a mock of
  CoSec's answers (the bodies of `wiki/integration/clients.md` are the ones CoSec writes).

## Supply chain

The CI job has `contents: read`, no secrets, a frozen lockfile and actions pinned by commit SHA. Nothing is published but
by a release the maintainer creates, in a job with the `npm-publish` environment and OIDC (no npm token), from tarballs
a job without that permission packed and checked.
