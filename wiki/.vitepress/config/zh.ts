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
import { REPO_URL } from './en'

// 与 en.ts 的侧边栏逐项对应，改一边就要改另一边。
const sidebar: DefaultTheme.SidebarItem[] = [
  {
    text: '指南',
    items: [
      { text: '简介', link: '/zh/guide/introduction' },
      { text: '快速开始', link: '/zh/guide/quick-start' },
      { text: '核心概念', link: '/zh/guide/concepts' },
      { text: '安全模型', link: '/zh/guide/security-model' },
      { text: '路线图', link: '/zh/guide/roadmap' },
    ],
  },
  {
    text: '集成',
    items: [
      { text: 'Web 集成', link: '/zh/integration/web' },
      { text: '认证', link: '/zh/integration/authentication' },
      { text: '代码中的权限检查', link: '/zh/integration/checks' },
    ],
  },
  {
    text: '策略',
    items: [
      { text: '策略语言', link: '/zh/policies/language' },
      { text: '角色权限', link: '/zh/policies/role-permissions' },
      { text: '限流', link: '/zh/policies/rate-limiting' },
      { text: '测试策略', link: '/zh/policies/testing' },
    ],
  },
  {
    text: '运维',
    items: [
      { text: '配置', link: '/zh/operations/configuration' },
      { text: '部署', link: '/zh/operations/deployment' },
      { text: '存储与缓存', link: '/zh/operations/storage' },
      { text: '升级', link: '/zh/operations/upgrading' },
      { text: '可观测性', link: '/zh/operations/observability' },
      { text: '故障排查', link: '/zh/operations/troubleshooting' },
    ],
  },
  {
    text: '架构',
    items: [
      { text: '概览', link: '/zh/architecture/overview' },
      { text: '扩展 CoSec', link: '/zh/architecture/extending' },
    ],
  },
]

export const zh: DefaultTheme.Config & { label: string, lang: string, link: string, title: string, description: string } = {
  label: '简体中文',
  lang: 'zh-CN',
  link: '/zh/',
  title: 'CoSec',
  description: '面向 Spring 的多租户响应式安全框架：IAM 风格的策略与基于角色的权限，按“拒绝优先”求值。',
  themeConfig: {
    nav: [
      { text: '指南', link: '/zh/guide/introduction' },
      { text: '集成', link: '/zh/integration/web' },
      { text: '策略', link: '/zh/policies/language' },
      { text: '运维', link: '/zh/operations/configuration' },
      { text: '架构', link: '/zh/architecture/overview' },
      { text: '发布记录', link: `${REPO_URL}/releases` },
    ],
    sidebar: {
      '/zh/': sidebar,
    },
    socialLinks: [
      { icon: 'github', link: REPO_URL },
    ],
    footer: {
      message: '基于 Apache License 2.0 发布',
      copyright: 'Copyright 2021-present Ahoo Wang',
    },
    editLink: {
      pattern: `${REPO_URL}/edit/main/wiki/:path`,
      text: '在 GitHub 上编辑此页',
    },
    docFooter: {
      prev: '上一页',
      next: '下一页',
    },
    outline: {
      label: '页面导航',
    },
    lastUpdated: {
      text: '最后更新于',
    },
    returnToTopLabel: '回到顶部',
    sidebarMenuLabel: '菜单',
    darkModeSwitchLabel: '主题',
    lightModeSwitchTitle: '切换到浅色模式',
    darkModeSwitchTitle: '切换到深色模式',
  },
}
