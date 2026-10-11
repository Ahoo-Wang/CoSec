# Contributing to CoSec

Thanks for helping improve CoSec. This guide describes how work flows from an issue to a release, and the
quality bar every change has to meet. For a tour of the codebase and the invariants every change must keep, read the
[architecture overview](https://cosec.ahoo.me/architecture/overview).

## 1. Start from an issue

Every change is traceable to an issue that states the requirement:

- **Bugs**: use the *Bug report* form: version, expected vs. actual behavior, and steps to reproduce.
- **Features**: use the *Feature request* form: the problem, the proposed behavior, and **acceptance
  criteria** that the change must satisfy.
- **Security vulnerabilities**: do **not** open a public issue. Follow [SECURITY.md](SECURITY.md).

Small fixes (typos, docs) may go straight to a pull request.

## 2. Branching model

CoSec uses trunk-based development:

- `main` is the only long-lived branch and is always releasable.
- Work happens on short-lived topic branches named after the change type, e.g. `feat/tenant-scoped-cache`
  or `fix/remote-ip-resolution`.
- Changes reach `main` only through pull requests, never by direct push.
- Keep a branch current by merging `main` into it. Do not rewrite history that others have reviewed.

## 3. Commits and pull request titles

Pull request titles follow [Conventional Commits](https://www.conventionalcommits.org/). A check enforces
this because the title drives labels, release notes, and the next version number:

```
<type>(<optional scope>)<optional !>: <description>
```

| Type | Use for | Release notes |
|---|---|---|
| `feat` | new behavior | 🎉 New Features |
| `fix` | bug fixes | 🐛 Bug Fixes |
| `perf` | performance improvements | ⚡ Performance Improvements |
| `docs` | documentation only | 📚 Documentation Updates |
| `refactor`, `test`, `build`, `ci`, `chore`, `style`, `revert` | everything else | 🔨 Maintenance |
| `fix(deps)` / `chore(deps)` | dependency updates | 🤖 Dependency Updates |

Mark breaking changes with `!` after the type (`feat!: ...`) or a `BREAKING CHANGE:` paragraph in the
description. They are listed under 💥 Breaking Changes and require a major version.

## 4. Coding conventions

- Kotlin 2.x targeting Java 17; formatting follows [.editorconfig](.editorconfig).
- [Detekt](config/detekt/detekt.yml) with `detekt-formatting` runs as part of `check`. It auto-corrects
  formatting locally, so commit the corrected files.
- Every source file starts with the Apache-2.0 license header in
  [config/detekt/license.template](config/detekt/license.template). Detekt fails the build otherwise.
- Public APIs are interfaces in `cosec-api`; implementations live in `cosec-core` and the integration
  modules. See the [architecture overview](https://cosec.ahoo.me/architecture/overview).

## 5. Tests and coverage

```bash
./gradlew check                                   # tests + detekt for all modules
./gradlew :cosec-core:test --tests "me.ahoo.cosec.policy.*"
./gradlew :code-coverage-report:codeCoverageReport
```

- **Unit tests**: JUnit 5, MockK, Hamcrest and FluentAssert, next to the code they cover.
- **Integration tests** run against real infrastructure. The Redis-backed modules (`cosec-cocache`,
  `cosec-spring-boot-starter`) expect Redis on `localhost:6379`:
  `docker run -d --name cosec-redis -p 6379:6379 redis:7`.
- **Coverage gates** (Codecov): changed lines need **≥ 80 %** coverage, and overall project coverage
  must not drop by more than 1 %.
- **Performance claims** come with a JMH benchmark (`src/jmh`), e.g.
  `./gradlew :cosec-core:jmh -PjmhIncludes=SimpleAuthorizationBenchmark`.

## 6. Pull requests and code review

- Fill in the pull request template: linked issue, what changed, and how it was tested.
- All CI checks must pass: per-module tests and detekt, Codecov, CodeQL, and the semantic title check.
- At least one approving review from a code owner ([CODEOWNERS](.github/CODEOWNERS)) is required.
- Reviewers check correctness (including fail-closed behavior for security decisions), tests, public API
  compatibility, and documentation.
- Labels are applied automatically from changed paths and the PR title.

## 7. Releases and semantic versioning

CoSec follows [Semantic Versioning](https://semver.org/):

- **MAJOR**: breaking changes to public APIs, configuration properties, or authorization semantics.
- **MINOR**: backwards-compatible features.
- **PATCH**: backwards-compatible fixes.

To release, bump `version` in [gradle.properties](gradle.properties) via a pull request, then publish a
GitHub release tagged `vX.Y.Z`. Publishing the release triggers the
[Packages Deploy](.github/workflows/package-deploy.yml) workflow (Maven Central and GitHub Packages).
Release notes are generated from pull request labels ([.github/release.yml](.github/release.yml)).

## 8. License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
