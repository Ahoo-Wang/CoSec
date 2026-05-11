# Wiki AGENTS.md

## Build & Run Commands

```bash
cd wiki
pnpm install
pnpm dev        # Dev server at http://localhost:5173
pnpm build      # Build static site
pnpm preview    # Preview built site
```

## Structure

- `.vitepress/config.mts` - VitePress configuration with i18n (en + zh)
- `.vitepress/theme/` - Custom theme with medium-zoom and Mermaid
- `getting-started/` - Quick start and configuration docs
- `deep-dive/` - Architecture, auth, authorization, integrations
- `onboarding/` - Audience-tailored onboarding guides
- `zh/` - Chinese translations
- `llms.txt` / `llms-full.txt` - LLM-friendly documentation

## Conventions

- Mermaid diagrams use dark-mode colors: fills `#2d333b`, borders `#6d5dfc`, text `#e6edf3`
- Never use `<br/>` in Mermaid labels (use `<br>` instead)
- Always use `autonumber` in sequence diagrams
- Source citations format: `[file_path:line](https://github.com/Ahoo-Wang/CoSec/blob/main/file_path#Lline)`
- Every page has VitePress frontmatter with `title` and `description`

## Boundaries

- ✅ Add new pages following existing structure
- ✅ Update sidebar in `.vitepress/config.mts` when adding pages
- ✅ Update `llms.txt` when adding new pages
- ⚠️ Ask before modifying theme or VitePress config
- 🚫 Never delete existing generated pages without confirmation
- 🚫 Never modify `.vitepress/theme/styles/index.css` without testing
