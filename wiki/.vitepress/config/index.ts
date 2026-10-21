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

import { defineConfig } from 'vitepress'
import { MermaidMarkdown } from 'vitepress-plugin-mermaid'
import { en } from './en'
import { zh } from './zh'

export default defineConfig({
  title: 'CoSec',
  description: en.description,
  // Fail the build on broken internal links.
  ignoreDeadLinks: false,
  // _legacy/ holds the pre-rewrite pages for reference while the new ones are written; AGENTS.md is for contributors.
  srcExclude: ['_legacy/**', 'AGENTS.md'],
  sitemap: {
    hostname: 'https://cosec.ahoo.me',
  },
  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/logo.svg' }],
    ['script', { async: '', src: 'https://www.googletagmanager.com/gtag/js?id=G-7BDR21FTDV' }],
    ['script', {}, "window.dataLayer = window.dataLayer || [];\nfunction gtag(){dataLayer.push(arguments);}\ngtag('js', new Date());\ngtag('config', 'G-7BDR21FTDV');"],
  ],
  themeConfig: {
    search: {
      provider: 'local',
      options: {
        detailedView: true,
      },
    },
  },
  locales: {
    root: {
      ...en,
    },
    zh: {
      ...zh,
      themeConfig: {
        ...zh.themeConfig,
        search: {
          provider: 'local',
          options: {
            detailedView: true,
            translations: {
              button: {
                buttonText: '搜索',
                buttonAriaLabel: '搜索文档',
              },
              modal: {
                displayDetails: '显示详情',
                resetButtonTitle: '清除搜索',
                backButtonTitle: '返回',
                noResultsText: '未找到相关结果',
                footer: {
                  selectText: '选择',
                  navigateText: '切换',
                  closeText: '关闭',
                },
              },
            },
          },
        },
      },
    },
  },
  markdown: {
    theme: {
      light: 'github-light',
      dark: 'github-dark',
    },
    config(md) {
      // Turns ```mermaid blocks into <Mermaid>, which theme/Mermaid.vue renders.
      md.use(MermaidMarkdown)
    },
  },
  vite: {
    optimizeDeps: {
      include: ['mermaid'],
    },
  },
})
