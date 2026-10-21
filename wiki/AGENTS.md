# AGENTS.md — CoSec Wiki (VitePress)

The user-facing docs site, published at https://cosec.ahoo.me from `main`. English at the root, Chinese
under `zh/`. Rules for changing CoSec itself are in the root [`AGENTS.md`](../AGENTS.md).

## Commands

Run from `wiki/`:

```bash
pnpm install
pnpm dev                 # dev server
pnpm check:mermaid       # parses every Mermaid diagram in headless Chrome and checks the conventions below
pnpm fix:mermaid         # rewrites <br/> to <br>, adds autonumber to sequence diagrams, and similar fixes
pnpm check:docs          # links, #anchors, en/zh heading parity, en/zh sidebar parity
pnpm build               # production build; fails on dead links
```

CI (`.github/workflows/deploy-wiki.yml`) runs `check:mermaid`, `check:docs` and `build` on every pull request
that touches `wiki/`. `check:mermaid` needs Chrome; set `CHROME_BIN` if it is not on `PATH`.

## Structure

Organized by the task the reader is doing, not by module.

| Area | Reader | Pages |
|---|---|---|
| `guide/` | Evaluating CoSec, or starting with it | `introduction`, `quick-start`, `concepts`, `security-model` |
| `integration/` | Developer wiring CoSec into an application | `web`, `authentication`, `checks` |
| `policies/` | Writing and reviewing the rules | `language`, `role-permissions`, `rate-limiting`, `testing` |
| `operations/` | Running CoSec in production | `configuration`, `storage`, `upgrading`, `observability`, `troubleshooting` |
| `architecture/` | Contributor, or integrator who wants the internals | `overview`, `extending` |
| `zh/` | One-to-one Chinese mirror of everything above | |
| `_legacy/` | Pre-rewrite pages, reference for the rewrite only; excluded from the build and the checks. Deleted when the rewrite is done | |

Navigation and sidebar live in `.vitepress/config/en.ts` and `zh.ts`; `check:docs` fails if the two differ or a
page is missing from the sidebar. Site-wide settings (domain, analytics, Mermaid, search) are in
`.vitepress/config/index.ts`.

## Page types

Every page is one type. Do not mix them: a reference page that starts teaching, or a guide that lists every
option, is harder to use for both jobs.

| Type | Answers | Shape | Pages |
|---|---|---|---|
| **Guide** | "How do I …?" | Steps or recipes toward one goal; links to references for every option | `quick-start`, `integration/*`, `role-permissions`, `rate-limiting`, `testing`, `storage`, `upgrading`, `observability`, `troubleshooting`, `extending` |
| **Reference** | "What exactly does X mean?" | Complete, terse, ordered for lookup (tables); no narrative | `policies/language`, `operations/configuration` |
| **Explanation** | "Why does it behave this way?" | Concepts, guarantees, trade-offs, diagrams | `introduction`, `concepts`, `security-model`, `architecture/overview` |

## One fact, one home

Each fact is written in exactly one place. Everywhere else links to it. A copied fact goes stale the first
time one copy is updated. The homes that are easiest to duplicate by accident:

| Fact | Home |
|---|---|
| Evaluation order; deny-first, default-deny and fail-closed guarantees and their exceptions | `guide/security-model.md` |
| Terms (principal, tenant, policy, statement, permission, decision) | `guide/concepts.md` |
| Module list and starter capabilities | `guide/introduction.md#modules` |
| Decision to HTTP status mapping; `CoSec-*` headers | `integration/web.md` |
| The explain API | `integration/checks.md#explaining-a-decision` |
| Every policy field, action and condition type | `policies/language.md` |
| Every `cosec.*` property and default | `operations/configuration.md` |
| Behavior when Redis is unavailable | `operations/storage.md#when-redis-is-unavailable` |
| Migration steps | `operations/upgrading.md` |
| Invariants and the tests that guard them; performance numbers | `architecture/overview.md` |

Outside the wiki: contribution workflow and releases in `CONTRIBUTING.md`, agent build rules in the root
`AGENTS.md`, API details in KDoc, JSON formats in `schema/*.schema.json`.

## Conventions

- **en and zh in pairs.** Every page under the root has a twin under `zh/` with the same headings in the same
  order. Every Chinese heading, including the `#` title, carries the English slug as an explicit anchor:
  `## 求值顺序 {#evaluation-order}`. `check:docs` compares the anchor sets.
- Every page has `title` and `description` frontmatter, then a short paragraph saying who the page is for and
  what problem it solves.
- **Verify every claim against the code**, not against other docs or `_legacy/`. A wrong doc is worse than a
  missing one. CoSec makes security decisions: say exactly when a request is allowed.
- **Link to files, never to line numbers.** Line anchors rot with every commit. Use relative links between
  pages (`../policies/language.md#actions`) so `check:docs` and the build can verify them.
- Write any text containing `<…>`, such as `Mono<T>`, in backticks; otherwise Vue parses it as HTML.

## Mermaid

`vitepress-plugin-mermaid` turns a ` ```mermaid ` block into the `<Mermaid>` component
(`.vitepress/theme/Mermaid.vue`), which renders it for the current light/dark theme and adds zoom and an
expanded view. So:

- Add a diagram only when it explains a mechanism (a request path, a decision, a sequence) better than prose.
- Line breaks in labels: `<br>`, never `<br/>`.
- Sequence diagrams start with `autonumber`, so the prose can refer to steps by number. Never put `;` in
  sequence message text; Mermaid reads it as a statement separator.
- No per-node `style`, `classDef` or colors: the theme handles light and dark mode.
- Run `pnpm check:mermaid` before pushing. It parses every diagram with the real Mermaid parser and fails on
  `<br/>` or a sequence diagram without `autonumber`; `pnpm fix:mermaid` fixes both.
