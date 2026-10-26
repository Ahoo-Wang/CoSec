/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { DefaultTheme } from 'vitepress'

export const REPO_URL = 'https://github.com/Ahoo-Wang/CoSec'

// One sidebar for the whole site: with 18 pages, seeing every area at once beats per-section sidebars.
const sidebar: DefaultTheme.SidebarItem[] = [
  {
    text: 'Guide',
    items: [
      { text: 'Introduction', link: '/guide/introduction' },
      { text: 'Quick Start', link: '/guide/quick-start' },
      { text: 'Concepts', link: '/guide/concepts' },
      { text: 'Security Model', link: '/guide/security-model' },
      { text: 'Roadmap', link: '/guide/roadmap' },
    ],
  },
  {
    text: 'Integration',
    items: [
      { text: 'Web Integration', link: '/integration/web' },
      { text: 'Authentication', link: '/integration/authentication' },
      { text: 'Checks in Code', link: '/integration/checks' },
    ],
  },
  {
    text: 'Policies',
    items: [
      { text: 'Policy Language', link: '/policies/language' },
      { text: 'Role Permissions', link: '/policies/role-permissions' },
      { text: 'Rate Limiting', link: '/policies/rate-limiting' },
      { text: 'Testing Policies', link: '/policies/testing' },
    ],
  },
  {
    text: 'Operations',
    items: [
      { text: 'Configuration', link: '/operations/configuration' },
      { text: 'Deployment', link: '/operations/deployment' },
      { text: 'Storage and Caching', link: '/operations/storage' },
      { text: 'Upgrading', link: '/operations/upgrading' },
      { text: 'Observability', link: '/operations/observability' },
      { text: 'Troubleshooting', link: '/operations/troubleshooting' },
    ],
  },
  {
    text: 'Architecture',
    items: [
      { text: 'Overview', link: '/architecture/overview' },
      { text: 'Extending CoSec', link: '/architecture/extending' },
    ],
  },
]

export const en: DefaultTheme.Config & { label: string, lang: string, title: string, description: string } = {
  label: 'English',
  lang: 'en',
  title: 'CoSec',
  description: 'Multi-tenant reactive security for Spring: IAM-style policies and role-based permissions, evaluated deny-first.',
  themeConfig: {
    nav: [
      { text: 'Guide', link: '/guide/introduction' },
      { text: 'Integration', link: '/integration/web' },
      { text: 'Policies', link: '/policies/language' },
      { text: 'Operations', link: '/operations/configuration' },
      { text: 'Architecture', link: '/architecture/overview' },
      { text: 'Releases', link: `${REPO_URL}/releases` },
    ],
    sidebar: {
      '/': sidebar,
    },
    socialLinks: [
      { icon: 'github', link: REPO_URL },
    ],
    footer: {
      message: 'Licensed under the Apache License, Version 2.0.',
      copyright: 'Copyright 2021-present Ahoo Wang',
    },
    editLink: {
      pattern: `${REPO_URL}/edit/main/wiki/:path`,
      text: 'Edit this page on GitHub',
    },
  },
}
