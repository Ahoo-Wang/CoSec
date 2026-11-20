// Holds the pages under public/problems/ to the problem types CoSec writes (UE-12). A problem document's `type` is
// `https://cosec.ahoo.me/problems/<slug>`, a wire contract that never changes; the URI has to open something, and the
// something is the section of operations/troubleshooting.md with the slug's anchor. Each slug of contract/problems/
// (one file per problem type, written by the server's tests) and of ProblemTypes.kt (the rows ProblemType is built over) needs
// public/problems/<slug>/index.html, a redirect to that section, and the section has to exist in English and Chinese.
// A new problem type without its page fails here, naming the file to write.
//
// Usage: node scripts/check-problem-pages.mjs   (from the wiki directory or via its path)
import { existsSync, readdirSync, readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { join } from 'path'
import { headingAnchor } from './headings.mjs'

const wikiDir = fileURLToPath(new URL('..', import.meta.url)).replace(/\/$/, '')
const repoDir = join(wikiDir, '..')
const problems = []

const fromContract = readdirSync(join(repoDir, 'contract/problems'))
  .filter((name) => name.endsWith('.json'))
  .map((name) => name.replace(/\.json$/, ''))
const kotlin = readFileSync(join(repoDir, 'core/document/src/main/kotlin/me/ahoo/cosec/document/format/ProblemTypes.kt'), 'utf-8')
const fromEnum = [...kotlin.matchAll(/^\s+Row\("([a-z-]+)",/gm)].map((match) => match[1])
const slugs = [...new Set([...fromContract, ...fromEnum])].sort()
for (const slug of fromEnum) if (!fromContract.includes(slug)) problems.push(`contract/problems/${slug}.json: missing for the problem type ${slug}`)
for (const slug of fromContract) if (!fromEnum.includes(slug)) problems.push(`ProblemTypes.kt: no problem type ${slug}, which contract/problems/ has`)

const page = (slug) => {
  const target = `/operations/troubleshooting#${slug}`
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${slug}: a CoSec problem type</title>
<link rel="canonical" href="https://cosec.ahoo.me${target}">
<meta http-equiv="refresh" content="0; url=${target}">
<script>location.replace(${JSON.stringify(target)})</script>
</head>
<body>
<p>The problem type <code>${slug}</code> is explained in <a href="${target}">Troubleshooting</a>.</p>
</body>
</html>
`
}

const sections = ['operations/troubleshooting.md', 'zh/operations/troubleshooting.md'].map((file) => {
  const anchors = new Set()
  for (const line of readFileSync(join(wikiDir, file), 'utf-8').replace(/```[\s\S]*?```/g, '').split('\n')) {
    const heading = /^#{1,6}\s+(.*?)\s*$/.exec(line)
    if (heading) anchors.add(headingAnchor(heading[1]))
  }
  return { file, anchors }
})

for (const slug of slugs) {
  const file = `public/problems/${slug}/index.html`
  const path = join(wikiDir, file)
  if (!existsSync(path)) {
    problems.push(`wiki/${file}: missing; it is the redirect for ${slug}'s type URI. Write it with:\n      node scripts/check-problem-pages.mjs --write`)
  } else if (readFileSync(path, 'utf-8') !== page(slug)) {
    problems.push(`wiki/${file}: differs from the page the check expects (node scripts/check-problem-pages.mjs --write rewrites it)`)
  }
  for (const { file: troubleshooting, anchors } of sections) {
    if (!anchors.has(slug)) problems.push(`wiki/${troubleshooting}: no section with the anchor #${slug}, which the type URI of ${slug} opens`)
  }
}

if (process.argv.includes('--write')) {
  const { mkdirSync, writeFileSync } = await import('fs')
  for (const slug of slugs) {
    mkdirSync(join(wikiDir, `public/problems/${slug}`), { recursive: true })
    writeFileSync(join(wikiDir, `public/problems/${slug}/index.html`), page(slug))
  }
  console.log(`wrote ${slugs.length} pages under public/problems/`)
  process.exit(0)
}

if (problems.length) {
  console.error(problems.map((p) => `  ❌ ${p}`).join('\n'))
  process.exit(1)
}
console.log(`✅ ${slugs.length} problem type URIs open their troubleshooting section.`)
