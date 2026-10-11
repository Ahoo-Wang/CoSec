import { defineConfig } from 'vitepress'

const REPO_URL = 'https://github.com/Ahoo-Wang/CoSec'

export default defineConfig({
    lang: 'en',
    title: 'CoSec',
    // Fail the build on broken internal links.
    ignoreDeadLinks: false,
    description: 'RBAC-based and Policy-based Multi-Tenant Reactive Security Framework',
    sitemap: {
        hostname: 'https://cosec.ahoo.me',
    },
    head: [
      ['link', { rel: 'icon', type: 'image/svg+xml', href: '/logo.svg' }],
      ['script', { async: '', src: 'https://www.googletagmanager.com/gtag/js?id=G-7BDR21FTDV' }],
      ['script', {}, "window.dataLayer = window.dataLayer || [];\nfunction gtag(){dataLayer.push(arguments);}\ngtag('js', new Date());\ngtag('config', 'G-7BDR21FTDV');"],
    ],
    locales: {
      root: {
        label: 'English',
        lang: 'en',
        themeConfig: {
          nav: [
            { text: 'Guide', link: '/guide/introduction' },
            { text: 'Architecture', link: '/architecture/overview' },
            { text: 'Releases', link: `${REPO_URL}/releases` },
          ],
          sidebar: {
            '/guide/': [
              {
                text: 'Guide',
                items: [
                  { text: 'Introduction', link: '/guide/introduction' },
                  { text: 'Quick Start', link: '/guide/quick-start' },
                  { text: 'Policies', link: '/guide/policies' },
                  { text: 'Configuration', link: '/guide/configuration' },
                  { text: 'Authentication', link: '/guide/authentication' },
                  { text: 'Web Integration', link: '/guide/web-integration' },
                  { text: 'Operations', link: '/guide/operations' },
                ],
              },
            ],
            '/architecture/': [
              {
                text: 'Architecture',
                items: [
                  { text: 'Overview', link: '/architecture/overview' },
                  { text: 'Extending', link: '/architecture/extending' },
                ],
              },
            ],
          },
          editLink: {
            pattern: `${REPO_URL}/edit/main/wiki/:path`,
            text: 'Edit this page on GitHub',
          },
          footer: {
            message: 'Licensed under the Apache License, Version 2.0.',
            copyright: 'Copyright 2021-present Ahoo Wang',
          },
          socialLinks: [
            { icon: 'github', link: REPO_URL },
          ],
        },
      },
      zh: {
        label: '简体中文',
        lang: 'zh-CN',
        link: '/zh/',
        themeConfig: {
          nav: [
            { text: '指南', link: '/zh/guide/introduction' },
            { text: '架构', link: '/zh/architecture/overview' },
            { text: '发布记录', link: `${REPO_URL}/releases` },
          ],
          sidebar: {
            '/zh/guide/': [
              {
                text: '指南',
                items: [
                  { text: '简介', link: '/zh/guide/introduction' },
                  { text: '快速开始', link: '/zh/guide/quick-start' },
                  { text: '策略', link: '/zh/guide/policies' },
                  { text: '配置', link: '/zh/guide/configuration' },
                  { text: '认证', link: '/zh/guide/authentication' },
                  { text: 'Web 集成', link: '/zh/guide/web-integration' },
                  { text: '运维', link: '/zh/guide/operations' },
                ],
              },
            ],
            '/zh/architecture/': [
              {
                text: '架构',
                items: [
                  { text: '概览', link: '/zh/architecture/overview' },
                  { text: '扩展', link: '/zh/architecture/extending' },
                ],
              },
            ],
          },
          editLink: {
            pattern: `${REPO_URL}/edit/main/wiki/:path`,
            text: '在 GitHub 上编辑此页',
          },
          footer: {
            message: '基于 Apache License 2.0 发布',
            copyright: 'Copyright 2021-present Ahoo Wang',
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
      },
    },
    themeConfig: {
      search: {
        provider: 'local',
      },
      socialLinks: [
        { icon: 'github', link: REPO_URL },
      ],
    },
    markdown: {
      theme: {
        light: 'github-light',
        dark: 'github-dark',
      },
    },
  })
