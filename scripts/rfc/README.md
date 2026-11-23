# RFC scripts

`check_refs.py` checks an RFC's references when it is consolidated (roadmap §4). Run it from the repository root with Python 3.10 or later:

```bash
python3 scripts/rfc/check_refs.py docs/rfc/0011-module-architecture.md --before <commit before consolidation>
```

It reports every citation elsewhere in the repository (`RFC 0011 §5`, `RFC 0011 D3`) whose section has no heading in the RFC, or whose label does not appear in it. It reports every section or label the RFC cites of another RFC that does not exist there. With `--before`, it reports every numbered heading of the old text that is gone or retitled. It exits 1 when anything is missing. Rule ids are left to `:conformance:check`. A review finding's id written right after a citation (a comment that names the RFC and then the finding that led to it) is reported too, since it reads like a label, and you decide whether it matters. Label tokens that disappeared from the old text are printed as notes, because a removed mention is not always a lost label. It is not run by CI.
