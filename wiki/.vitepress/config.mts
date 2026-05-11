import { defineConfig } from 'vitepress'

const REPO_URL = 'https://github.com/Ahoo-Wang/CoSec'

export default defineConfig({
    lang: 'en',
    title: 'CoSec',
    ignoreDeadLinks: true,
    description: 'RBAC-based and Policy-based Multi-Tenant Reactive Security Framework',
    head: [
      ['link', { rel: 'icon', type: 'image/svg+xml', href: '/logo.svg' }],
    ],
    locales: {
      root: {
        label: 'English',
        lang: 'en',
        themeConfig: {
          nav: [
            { text: 'Guide', link: '/getting-started/overview' },
            { text: 'Deep Dive', link: '/deep-dive/architecture/security-model' },
            {
              text: 'Integrations',
              items: [
                { text: 'Spring WebFlux', link: '/deep-dive/integrations/spring-webflux' },
                { text: 'Spring WebMvc', link: '/deep-dive/integrations/spring-webmvc' },
                { text: 'Spring Cloud Gateway', link: '/deep-dive/integrations/spring-cloud-gateway' },
                { text: 'Redis Caching', link: '/deep-dive/integrations/redis-caching' },
              ],
            },
            {
              text: 'Resources',
              items: [
                { text: 'API Reference', link: '/deep-dive/extending/custom-matchers' },
                { text: 'Onboarding', link: '/onboarding/' },
              ],
            },
          ],
          sidebar: {
            '/getting-started/': [
              {
                text: 'Getting Started',
                collapsed: false,
                items: [
                  { text: 'Overview', link: '/getting-started/overview' },
                  { text: 'Quick Start', link: '/getting-started/quick-start' },
                  { text: 'Configuration Reference', link: '/getting-started/configuration' },
                  { text: 'Policy Authoring Guide', link: '/getting-started/policy-authoring' },
                ],
              },
            ],
            '/deep-dive/': [
              {
                text: 'Architecture',
                collapsed: false,
                items: [
                  { text: 'Module Dependency Graph', link: '/deep-dive/architecture/module-dependency' },
                  { text: 'Security Model', link: '/deep-dive/architecture/security-model' },
                  { text: 'Reactive Design', link: '/deep-dive/architecture/reactive-design' },
                  { text: 'Multi-Tenancy', link: '/deep-dive/architecture/multi-tenancy' },
                ],
              },
              {
                text: 'Authentication',
                collapsed: true,
                items: [
                  { text: 'Authentication System', link: '/deep-dive/authentication/authentication-system' },
                  { text: 'JWT Integration', link: '/deep-dive/authentication/jwt-integration' },
                  { text: 'Social Authentication', link: '/deep-dive/authentication/social-authentication' },
                  { text: 'Token Management', link: '/deep-dive/authentication/token-management' },
                ],
              },
              {
                text: 'Authorization',
                collapsed: true,
                items: [
                  { text: 'Authorization Flow', link: '/deep-dive/authorization/authorization-flow' },
                  { text: 'Policy Evaluation', link: '/deep-dive/authorization/policy-evaluation' },
                  { text: 'Action Matchers', link: '/deep-dive/authorization/action-matchers' },
                  { text: 'Condition Matchers', link: '/deep-dive/authorization/condition-matchers' },
                  { text: 'Permissions and Roles', link: '/deep-dive/authorization/permissions-roles' },
                ],
              },
              {
                text: 'Integrations',
                collapsed: true,
                items: [
                  { text: 'Spring WebFlux', link: '/deep-dive/integrations/spring-webflux' },
                  { text: 'Spring WebMvc', link: '/deep-dive/integrations/spring-webmvc' },
                  { text: 'Spring Cloud Gateway', link: '/deep-dive/integrations/spring-cloud-gateway' },
                  { text: 'Redis Caching', link: '/deep-dive/integrations/redis-caching' },
                  { text: 'OpenTelemetry', link: '/deep-dive/integrations/opentelemetry' },
                  { text: 'OpenAPI / Swagger', link: '/deep-dive/integrations/openapi' },
                  { text: 'IP Geolocation', link: '/deep-dive/integrations/ip-geolocation' },
                ],
              },
              {
                text: 'Extending CoSec',
                collapsed: true,
                items: [
                  { text: 'Custom Matchers (SPI)', link: '/deep-dive/extending/custom-matchers' },
                  { text: 'Auto-Configuration', link: '/deep-dive/extending/auto-configuration' },
                ],
              },
              {
                text: 'Operations',
                collapsed: true,
                items: [
                  { text: 'Deployment', link: '/deep-dive/operations/deployment' },
                  { text: 'Testing Guide', link: '/deep-dive/operations/testing' },
                  { text: 'Performance and Benchmarks', link: '/deep-dive/operations/performance' },
                ],
              },
            ],
            '/onboarding/': [
              {
                text: 'Onboarding',
                collapsed: false,
                items: [
                  { text: 'Contributor Guide', link: '/onboarding/contributor' },
                  { text: 'Staff Engineer Guide', link: '/onboarding/staff-engineer' },
                  { text: 'Executive Guide', link: '/onboarding/executive' },
                  { text: 'Product Manager Guide', link: '/onboarding/product-manager' },
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
          search: {
            provider: 'local',
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
            { text: '指南', link: '/zh/getting-started/overview' },
            { text: '深入', link: '/zh/deep-dive/architecture/security-model' },
            {
              text: '集成',
              items: [
                { text: 'Spring WebFlux', link: '/zh/deep-dive/integrations/spring-webflux' },
                { text: 'Spring WebMvc', link: '/zh/deep-dive/integrations/spring-webmvc' },
                { text: 'Spring Cloud Gateway', link: '/zh/deep-dive/integrations/spring-cloud-gateway' },
                { text: 'Redis 缓存', link: '/zh/deep-dive/integrations/redis-caching' },
              ],
            },
          ],
          sidebar: {
            '/zh/getting-started/': [
              {
                text: '快速开始',
                collapsed: false,
                items: [
                  { text: '概述', link: '/zh/getting-started/overview' },
                  { text: '快速入门', link: '/zh/getting-started/quick-start' },
                  { text: '配置参考', link: '/zh/getting-started/configuration' },
                  { text: '策略编写指南', link: '/zh/getting-started/policy-authoring' },
                ],
              },
            ],
            '/zh/deep-dive/': [
              {
                text: '架构',
                collapsed: false,
                items: [
                  { text: '模块依赖图', link: '/zh/deep-dive/architecture/module-dependency' },
                  { text: '安全模型', link: '/zh/deep-dive/architecture/security-model' },
                  { text: '响应式设计', link: '/zh/deep-dive/architecture/reactive-design' },
                  { text: '多租户', link: '/zh/deep-dive/architecture/multi-tenancy' },
                ],
              },
              {
                text: '认证',
                collapsed: true,
                items: [
                  { text: '认证系统', link: '/zh/deep-dive/authentication/authentication-system' },
                  { text: 'JWT 集成', link: '/zh/deep-dive/authentication/jwt-integration' },
                  { text: '社交认证', link: '/zh/deep-dive/authentication/social-authentication' },
                  { text: '令牌管理', link: '/zh/deep-dive/authentication/token-management' },
                ],
              },
              {
                text: '授权',
                collapsed: true,
                items: [
                  { text: '授权流程', link: '/zh/deep-dive/authorization/authorization-flow' },
                  { text: '策略评估', link: '/zh/deep-dive/authorization/policy-evaluation' },
                  { text: '动作匹配器', link: '/zh/deep-dive/authorization/action-matchers' },
                  { text: '条件匹配器', link: '/zh/deep-dive/authorization/condition-matchers' },
                  { text: '权限和角色', link: '/zh/deep-dive/authorization/permissions-roles' },
                ],
              },
              {
                text: '集成',
                collapsed: true,
                items: [
                  { text: 'Spring WebFlux', link: '/zh/deep-dive/integrations/spring-webflux' },
                  { text: 'Spring WebMvc', link: '/zh/deep-dive/integrations/spring-webmvc' },
                  { text: 'Spring Cloud Gateway', link: '/zh/deep-dive/integrations/spring-cloud-gateway' },
                  { text: 'Redis 缓存', link: '/zh/deep-dive/integrations/redis-caching' },
                  { text: 'OpenTelemetry', link: '/zh/deep-dive/integrations/opentelemetry' },
                  { text: 'OpenAPI / Swagger', link: '/zh/deep-dive/integrations/openapi' },
                  { text: 'IP 地理位置', link: '/zh/deep-dive/integrations/ip-geolocation' },
                ],
              },
              {
                text: '扩展 CoSec',
                collapsed: true,
                items: [
                  { text: '自定义匹配器 (SPI)', link: '/zh/deep-dive/extending/custom-matchers' },
                  { text: '自动配置', link: '/zh/deep-dive/extending/auto-configuration' },
                ],
              },
              {
                text: '运维',
                collapsed: true,
                items: [
                  { text: '部署', link: '/zh/deep-dive/operations/deployment' },
                  { text: '测试指南', link: '/zh/deep-dive/operations/testing' },
                  { text: '性能与基准测试', link: '/zh/deep-dive/operations/performance' },
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
          search: {
            provider: 'local',
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
