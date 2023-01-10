# CoSec

RBAC-based And Policy-based Multi-Tenant Reactive Security Framework.

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)
[![GitHub release](https://img.shields.io/github/release/Ahoo-Wang/CoSec.svg)](https://github.com/Ahoo-Wang/CoSec/releases)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/me.ahoo.cosec/cosec-core/badge.svg)](https://maven-badges.herokuapp.com/maven-central/me.ahoo.cosec/cosec-core)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/b3133cf684a74192a55abbefe2a0759a)](https://www.codacy.com/gh/Ahoo-Wang/CoSec/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=Ahoo-Wang/CoSec&amp;utm_campaign=Badge_Grade)
[![codecov](https://codecov.io/gh/Ahoo-Wang/CoSec/branch/main/graph/badge.svg?token=AL0RyJbMZv)](https://codecov.io/gh/Ahoo-Wang/CoSec)
![Integration Test Status](https://github.com/Ahoo-Wang/CoSec/actions/workflows/integration-test.yml/badge.svg)

## Authentication

![Authentication-Flow](document/design/assets/Authentication-Flow.svg)

## Authorization

![Authorization-Flow](document/design/assets/Authorization-Flow.svg)

## OAuth

![OAuth-Flow](document/design/assets/OAuth-Flow.svg)

## Modeling

![Modeling](document/design/assets/Modeling.svg)

## Gateway

![Gateway](document/design/assets/Gateway.svg)

## Authorization Policy

![Authorization Policy](document/design/assets/Authorization-Policy.svg)

## Build In Policy

### ActionMatcher

![ActionMatcher](document/design/assets/ActionMatcher.svg)

#### How to customize `ActionMatcher` (SPI)

> Refer to [RegularActionMatcher](cosec-core/src/main/kotlin/me/ahoo/cosec/policy/action/RegularActionMatcher.kt) 

```kotlin
class CustomActionMatcherFactory : ActionMatcherFactory {
    companion object {
        const val TYPE = "[CustomActionType]"
    }

    override val type: String
        get() = TYPE

    override fun create(onfiguration: Configuration): ActionMatcher {
        return CustomActionMatcher(onfiguration)
    }
}
class CustomActionMatcher(configuration: Configuration) :
    AbstractActionMatcher(CustomActionMatcherFactory.TYPE, configuration) {

    override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
        //Custom matching logic
    }
}
```

> META-INF/services/me.ahoo.cosec.policy.action.ActionMatcherFactory

```properties
# CustomActionMatcherFactory fully qualified name
```

### ConditionMatcher

![ConditionMatcher](document/design/assets/ConditionMatcher.svg)

#### How to customize `ConditionMatcher` (SPI)

> Refer to [ContainsConditionMatcher](cosec-core/src/main/kotlin/me/ahoo/cosec/policy/condition/part/ContainsConditionMatcher.kt)

```kotlin
class CustomConditionMatcherFactory : ConditionMatcherFactory {
    companion object {
        const val TYPE = "[CustomConditionType]"
    }

    override val type: String
        get() = TYPE

    override fun create(configuration: Configuration): ConditionMatcher {
        return CustomConditionMatcher(configuration)
    }
}
class CustomConditionMatcher(configuration: Configuration) :
    AbstractActionMatcher(CustomActionMatcherFactory.TYPE, configuration) {

    override fun internalMatch(request: Request, securityContext: SecurityContext): Boolean {
        //Custom matching logic
    }
}
```

> META-INF/services/me.ahoo.cosec.policy.condition.ConditionMatcherFactory

```properties
# CustomConditionMatcherFactory fully qualified name
```

## Policy Schema

Configure [Policy Schema](document/cosec-policy.schema.json) to support IDE ([IntelliJ IDEA](https://www.jetbrains.com/help/idea/json.html#ws_json_using_schemas)) input autocompletion.

> Policy Demo

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
      "condition": {
        "type": "authenticated"
      }
    },
    {
      "name": "Developer",
      "effect": "allow",
      "actions": [
        {
          "type": "all"
        }
      ],
      "condition": {
        "type": "in",
        "part": "context.principal.id",
        "in": [
          "developerId"
        ]
      }
    },
    {
      "name": "RequestOriginDeny",
      "effect": "deny",
      "actions": [
        {
          "type": "all"
        }
      ],
      "condition": {
        "type": "reg",
        "negate": true,
        "part": "request.origin",
        "pattern": "^(http|https)://github.com"
      }
    },
    {
      "name": "IpBlacklist",
      "effect": "deny",
      "actions": [
        {
          "type": "all"
        }
      ],
      "condition": {
        "type": "path",
        "part": "request.remoteIp",
        "path": {
          "caseSensitive": false,
          "separator": ".",
          "decodeAndParseSegments": false
        },
        "pattern": "192.168.0.*"
      }
    },
    {
      "name": "RegionWhitelist",
      "effect": "deny",
      "actions": [
        {
          "type": "all"
        }
      ],
      "condition": {
        "negate": true,
        "type": "reg",
        "part": "request.attributes.ipRegion",
        "pattern": "^中国\\|0\\|(上海|广东省)\\|.*"
      }
    },
    {
      "name": "AllowDeveloperOrIpRange",
      "effect": "allow",
      "actions": [
        {
          "type": "all"
        }
      ],
      "condition": {
        "type": "bool",
        "bool": {
          "and": [
            {
              "type": "authenticated"
            }
          ],
          "or": [
            {
              "type": "in",
              "part": "context.principal.id",
              "in": [
                "developerId"
              ]
            },
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
      }
    }
  ]
}

```

## OpenTelemetry

[CoSec-OpenTelemetry](cosec-opentelemetry)

> CoSec follows the OpenTelemetry [General identity attributes](https://opentelemetry.io/docs/reference/specification/trace/semantic_conventions/span-general/#general-identity-attributes) specification。

![CoSec-OpenTelemetry](document/design/assets/CoSec-OpenTelemetry.png)

## Thanks

CoSec permission policy design refers to [AWS IAM](https://docs.aws.amazon.com/IAM/latest/UserGuide/introduction.html) .
