// Checks what the VitePress build does not:
// - every internal link's #anchor exists on the target page;
// - every English page has a Chinese twin under zh/ with the same set of heading anchors
//   (Chinese headings declare explicit `{#english-slug}` anchors);
// - the en and zh sidebars in config/{en,zh}.ts list the same pages in the same order, every link
//   points to an existing page, and every page except the home pages is in the sidebar.
// - every https://cosec.ahoo.me/... link of llms.txt, the skills and the READMEs names a page and an anchor that exist,
//   and the site build publishes llms.txt (config/index.ts copies it from the repository root);
// - every page of the sidebar has a link in llms.txt.
// - every https://cosec.ahoo.me/schema/<line>/<file> link names a file of the repository's schema/, which the build serves.
// - a JSON block that README*.md or a skill copies from an example carries the marker
//   `<!-- sample: wiki/examples/<dir>/documents/<file>.json -->` on the line before its fence, and must equal that file
//   (the example is compiled and run by DocumentationExamplesTest, so the copy cannot drift from what CoSec accepts).
// AGENTS.md is skipped.
// Usage: node .vitepress/check-docs.mjs   (from the wiki directory or via its path)
import { readFileSync, readdirSync, statSync, existsSync } from 'fs'
import { join, dirname, normalize, relative } from 'path'
import { headingAnchor } from '../scripts/headings.mjs'

const wikiDir = new URL('..', import.meta.url).pathname.replace(/\/$/, '')

function walk(dir) {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry)
    if (entry === 'node_modules' || entry.startsWith('.')) return []
    if (statSync(full).isDirectory()) return walk(full)
    return entry.endsWith('.md') && entry !== 'AGENTS.md' ? [full] : []
  })
}

const stripCode = (text) => text.replace(/```[\s\S]*?```/g, '')

const anchorCache = new Map()
function anchors(file) {
  if (anchorCache.has(file)) return anchorCache.get(file)
  const result = new Set()
  for (const line of stripCode(readFileSync(file, 'utf-8')).split('\n')) {
    const heading = /^#{1,6}\s+(.*?)\s*$/.exec(line)
    if (!heading) continue
    result.add(headingAnchor(heading[1]))
  }
  anchorCache.set(file, result)
  return result
}

function resolveTarget(from, target) {
  if (target === '') return from
  if (target.startsWith('/')) {
    const base = target.replace(/\/$/, '') || '/index'
    return join(wikiDir, base.endsWith('.md') ? base : `${base}.md`)
  }
  const path = normalize(join(dirname(from), target))
  return path.endsWith('.md') ? path : `${path}.md`
}

const pages = walk(wikiDir)
const problems = []
const rel = (file) => relative(wikiDir, file)
const zhDir = join(wikiDir, 'zh')

for (const page of pages) {
  if (page.startsWith(zhDir + '/')) {
    if (!existsSync(join(wikiDir, relative(zhDir, page)))) problems.push(`${rel(page)}: no English twin`)
    continue
  }
  const twin = join(zhDir, relative(wikiDir, page))
  if (!existsSync(twin)) {
    problems.push(`${rel(page)}: no Chinese twin under zh/`)
    continue
  }
  if (page.endsWith('index.md')) continue
  const en = anchors(page)
  const zh = anchors(twin)
  const onlyEn = [...en].filter((a) => !zh.has(a))
  const onlyZh = [...zh].filter((a) => !en.has(a))
  if (onlyEn.length || onlyZh.length) {
    problems.push(`${rel(page)}: anchors differ from zh twin (only en: ${onlyEn.join(', ') || '-'}; only zh: ${onlyZh.join(', ') || '-'})`)
  }
}

let checked = 0
for (const page of pages) {
  const text = stripCode(readFileSync(page, 'utf-8'))
  const links = [
    ...[...text.matchAll(/\]\(([^)\s]+)\)/g)].map((m) => m[1]),
    ...[...text.matchAll(/^\s*link:\s*(\S+)/gm)].map((m) => m[1]),
  ]
  for (const link of links) {
    if (/^(https?:|mailto:)/.test(link)) continue
    checked++
    const [target, fragment] = link.split('#')
    const file = resolveTarget(page, target)
    if (!existsSync(file)) {
      problems.push(`${rel(page)}: broken link ${link}`)
    } else if (fragment && !anchors(file).has(fragment)) {
      problems.push(`${rel(page)}: missing anchor #${fragment} in ${rel(file)}`)
    }
  }
}

// What the repository says outside the wiki about the wiki: llms.txt (served by the build), the skills, the READMEs.
const repoRoot = join(wikiDir, '..')
const outside = [
  join(repoRoot, 'llms.txt'),
  ...readdirSync(repoRoot).filter((f) => /^README.*\.md$/.test(f)).map((f) => join(repoRoot, f)),
  ...(existsSync(join(repoRoot, 'skills'))
    ? readdirSync(join(repoRoot, 'skills'))
        .map((d) => join(repoRoot, 'skills', d, 'SKILL.md'))
        .filter((f) => existsSync(f))
    : []),
]
for (const file of outside) {
  const relative_ = relative(repoRoot, file)
  for (const m of readFileSync(file, 'utf-8').matchAll(/https:\/\/cosec\.ahoo\.me(\/[^\s)`'"<>\]]*)?/g)) {
    checked++
    const [path, fragment] = (m[1] ?? '/').replace(/[.,;:]+$/, '').split('#')
    if (path === '/' || path === '/zh/' || path === '/llms.txt') continue
    if (path.startsWith('/schema/')) continue // held by the schema rule below
    const page = resolveTarget(wikiDir, path)
    if (!existsSync(page)) problems.push(`${relative_}: link ${m[0]} has no page`)
    else if (fragment && !anchors(page).has(fragment)) problems.push(`${relative_}: missing anchor #${fragment} in ${rel(page)}`)
  }
}
// The site serves schema/ at /schema/ (config/index.ts copies it in buildEnd), so a link to https://cosec.ahoo.me/schema/<line>/<file>,
// anywhere, in a code block too, names a file that exists in the repository's schema/<line>/.
const schemaLink = /https:\/\/cosec\.ahoo\.me\/schema\/([^\s)`'"<>\]]*)/g
for (const file of [...pages, ...outside]) {
  for (const m of readFileSync(file, 'utf-8').matchAll(schemaLink)) {
    checked++
    const target = m[1].replace(/[.,;:]+$/, '')
    // A file of a line, or the line's directory (`.../schema/8.0/<file>` in prose ends at the `<`).
    if (!/^[0-9]+\.[0-9]+(\/[a-z-]+\.schema\.json|\/?)$/.test(target) || !existsSync(join(repoRoot, 'schema', target))) {
      problems.push(`${relative(repoRoot, file)}: link ${m[0]} names no file of schema/ (the site serves schema/<line>/<file>)`)
    }
  }
}
const samplePattern = /<!--\s*sample:\s*(\S+)\s*-->\s*\n```json\n([\s\S]*?)\n```/g
for (const file of outside) {
  const relative_ = relative(repoRoot, file)
  for (const m of readFileSync(file, 'utf-8').matchAll(samplePattern)) {
    checked++
    const source = join(repoRoot, m[1])
    if (!existsSync(source)) {
      problems.push(`${relative_}: sample ${m[1]} does not exist`)
      continue
    }
    try {
      const same = JSON.stringify(sortKeys(JSON.parse(m[2]))) === JSON.stringify(sortKeys(JSON.parse(readFileSync(source, 'utf-8'))))
      if (!same) problems.push(`${relative_}: the JSON block marked sample ${m[1]} differs from that file`)
    } catch (e) {
      problems.push(`${relative_}: the JSON block marked sample ${m[1]} does not parse (${e.message})`)
    }
  }
}
function sortKeys(value) {
  if (Array.isArray(value)) return value.map(sortKeys)
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map((k) => [k, sortKeys(value[k])]))
  return value
}
if (!/llms\.txt/.test(readFileSync(join(wikiDir, '.vitepress/config/index.ts'), 'utf-8'))) {
  problems.push('config/index.ts: the build does not publish llms.txt (copy it in buildEnd)')
}
if (!/siteConfig\.outDir,\s*'schema'/.test(readFileSync(join(wikiDir, '.vitepress/config/index.ts'), 'utf-8'))) {
  problems.push('config/index.ts: the build does not publish schema/ (copy it to <outDir>/schema in buildEnd)')
}

const sidebarLinks = (locale) =>
  [...readFileSync(join(wikiDir, '.vitepress/config', `${locale}.ts`), 'utf-8').matchAll(/link:\s*'(\/[^']*)'/g)]
    .map((m) => m[1])
    .filter((link) => !link.endsWith('/') || link === '/')
const enLinks = sidebarLinks('en')
const zhLinks = sidebarLinks('zh')
if (enLinks.map((l) => `/zh${l}`).join() !== zhLinks.join()) {
  problems.push(`config: en and zh nav/sidebar links differ (en: ${enLinks.join(' ')}; zh: ${zhLinks.join(' ')})`)
}
for (const link of [...enLinks, ...zhLinks]) {
  if (!existsSync(resolveTarget(wikiDir, link.split('#')[0]))) problems.push(`config: link ${link} has no page`)
}
const llms = readFileSync(join(repoRoot, 'llms.txt'), 'utf-8')
for (const link of enLinks) {
  if (link === '/' || link.endsWith('/')) continue
  if (!llms.includes(`https://cosec.ahoo.me${link.split('#')[0]})`)) problems.push(`llms.txt: no link to the page ${link} of the sidebar`)
}
const listed = new Set([...enLinks, ...zhLinks].map((l) => l.split('#')[0]))
for (const page of pages) {
  if (page.endsWith('index.md')) continue
  const link = `/${rel(page).replace(/\.md$/, '')}`
  if (!listed.has(link)) problems.push(`${rel(page)}: not in the sidebar`)
}

console.log(`${pages.length} pages, ${checked} internal links checked`)
if (problems.length) {
  console.error(problems.map((p) => `  ❌ ${p}`).join('\n'))
  process.exit(1)
}
console.log('✅ Links, anchors and en/zh parity OK.')
