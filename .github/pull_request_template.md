<!--
Title: use Conventional Commits, e.g. `fix(cocache): ...`, `feat!: ...` (enforced; it drives labels,
release notes and the next version). See CONTRIBUTING.md.
-->

Fixes #

## What changed and why

## How it was tested

<!-- Unit / integration tests added or updated; for performance claims, include JMH results. -->

## Checklist

- [ ] Linked issue states the requirement (or this is a trivial fix)
- [ ] Tests cover the change; `./gradlew check` passes locally
- [ ] Security decisions fail closed (errors deny, never allow)
- [ ] Docs updated (wiki EN/ZH, configuration reference) where behavior or properties changed
- [ ] Breaking changes are marked with `!` / `BREAKING CHANGE:` and described above
