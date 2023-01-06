# CoSec

基于 RBAC 和策略的多租户响应式安全框架。

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)
[![GitHub release](https://img.shields.io/github/release/Ahoo-Wang/CoSec.svg)](https://github.com/Ahoo-Wang/CoSec/releases)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/me.ahoo.cosec/cosec-core/badge.svg)](https://maven-badges.herokuapp.com/maven-central/me.ahoo.cosec/cosec-core)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/b3133cf684a74192a55abbefe2a0759a)](https://www.codacy.com/gh/Ahoo-Wang/CoSec/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=Ahoo-Wang/CoSec&amp;utm_campaign=Badge_Grade)
[![codecov](https://codecov.io/gh/Ahoo-Wang/CoSec/branch/main/graph/badge.svg?token=AL0RyJbMZv)](https://codecov.io/gh/Ahoo-Wang/CoSec)
![Integration Test Status](https://github.com/Ahoo-Wang/CoSec/actions/workflows/integration-test.yml/badge.svg)

## 认证

![Authentication-Flow](document/design/assets/Authentication-Flow.svg)

## 授权

![Authorization-Flow](document/design/assets/Authorization-Flow.svg)

## OAuth

![OAuth-Flow](document/design/assets/OAuth-Flow.svg)

## 建模类图

![Modeling](document/design/assets/Modeling.svg)

## 安全网关服务

![Gateway](document/design/assets/Gateway.svg)

## 授权策略流程

![Authorization Policy](document/design/assets/Authorization-Policy.svg)

## 内置策略匹配器

### ActionMatcher

![ActionMatcher](document/design/assets/ActionMatcher.svg)

### ConditionMatcher

![ConditionMatcher](document/design/assets/ConditionMatcher.svg)

## 策略 Schema

[Policy Schema](document/cosec-policy.schema.json)

```json
{
  "id": "id",
  "name": "name",
  "category": "category",
  "description": "description",
  "type": "global",
  "tenantId": "tenantId",
  "statements": [
    {
      "name": "Anonymous",
      "effect": "allow",
      "actions": [
        {
          "type": "path",
          "pattern": "/auth/register"
        },
        {
          "type": "path",
          "pattern": "/auth/login"
        }
      ]
    },
    {
      "name": "UserScope",
      "effect": "allow",
      "actions": [
        {
          "type": "path",
          "pattern": "/user/#{principal.id}/*"
        }
      ],
      "conditions": [
        {
          "type": "authenticated"
        }
      ]
    },
    {
      "name": "Developer",
      "effect": "allow",
      "actions": [
        {
          "type": "all"
        }
      ],
      "conditions": [
        {
          "type": "in",
          "part": "context.principal.id",
          "in": [
            "developerId"
          ]
        }
      ]
    },
    {
      "name": "RequestOriginDeny",
      "effect": "deny",
      "actions": [
        {
          "type": "all"
        }
      ],
      "conditions": [
        {
          "type": "reg",
          "negate": true,
          "part": "request.origin",
          "pattern": "^(http|https)://github.com"
        }
      ]
    },
    {
      "name": "IpBlacklist",
      "effect": "deny",
      "actions": [
        {
          "type": "all"
        }
      ],
      "conditions": [
        {
          "type": "path",
          "part": "request.remoteIp",
          "path": {
            "caseSensitive": false,
            "separator": ".",
            "decodeAndParseSegments": false
          },
          "pattern": "192.168.0.*"
        }
      ]
    }
  ]
}
```

## 感谢

CoSec 权限策略设计参考 [AWS IAM](https://docs.aws.amazon.com/IAM/latest/UserGuide/introduction.html) 。
