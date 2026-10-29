// Heading anchors as VitePress computes them, shared by the docs checks.

// Mirrors VitePress' default slugify (@mdit-vue/shared).
const rSpecial = /[\s~`!@#$%^&*()\-_+=[\]{}|\\;:"'“”‘’<>,.?/]+/g
export function slugify(str) {
  return str
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(rSpecial, '-')
    .replace(/-{2,}/g, '-')
    .replace(/^-+|-+$/g, '')
    .replace(/^(\d)/, '_$1')
    .toLowerCase()
}

// The anchor of a heading, given the text after its `#`s: an explicit `{#english-slug}` wins.
export function headingAnchor(text) {
  const explicit = /\{#([^}]+)\}\s*$/.exec(text)
  return explicit ? explicit[1] : slugify(text.replace(/`([^`]*)`/g, '$1'))
}
