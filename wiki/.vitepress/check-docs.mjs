// Checks what the VitePress build does not:
// - every internal link's #anchor exists on the target page;
// - every English page has a Chinese twin under zh/ with the same set of heading anchors
//   (Chinese headings declare explicit `{#english-slug}` anchors);
// - the en and zh sidebars in config/{en,zh}.ts list the same pages in the same order, every link
//   points to an existing page, and every page except the home pages is in the sidebar.
// AGENTS.md is skipped.
// Usage: node .vitepress/check-docs.mjs   (from the wiki directory or via its path)
import { readFileSync, readdirSync, statSync, existsSync } from 'fs'
import { join, dirname, normalize, relative } from 'path'

const wikiDir = new URL('..', import.meta.url).pathname.replace(/\/$/, '')

function walk(dir) {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry)
    if (entry === 'node_modules' || entry.startsWith('.')) return []
    if (statSync(full).isDirectory()) return walk(full)
    return entry.endsWith('.md') && entry !== 'AGENTS.md' ? [full] : []
  })
}

// Mirrors VitePress' default slugify (@mdit-vue/shared).
const rSpecial = /[\s~`!@#$%^&*()\-_+=[\]{}|\\;:"'“”‘’<>,.?/]+/g
function slugify(str) {
  return str
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(rSpecial, '-')
    .replace(/-{2,}/g, '-')
    .replace(/^-+|-+$/g, '')
    .replace(/^(\d)/, '_$1')
    .toLowerCase()
}

const stripCode = (text) => text.replace(/```[\s\S]*?```/g, '')

const anchorCache = new Map()
function anchors(file) {
  if (anchorCache.has(file)) return anchorCache.get(file)
  const result = new Set()
  for (const line of stripCode(readFileSync(file, 'utf-8')).split('\n')) {
    const heading = /^#{1,6}\s+(.*?)\s*$/.exec(line)
    if (!heading) continue
    const explicit = /\{#([^}]+)\}\s*$/.exec(heading[1])
    result.add(explicit ? explicit[1] : slugify(heading[1].replace(/`([^`]*)`/g, '$1')))
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
