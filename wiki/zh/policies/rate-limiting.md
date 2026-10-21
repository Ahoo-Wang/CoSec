---
title: "限流"
description: "限制已放行请求的频率，可以按调用方、路径或其他任意键计数。"
---

# 限流 {#rate-limiting}

面向需要防止 API 被过度调用的策略作者和运维人员。限流在授权之后进行，只为已放行的请求消耗配额；本页介绍如何编写规则、选择计数后端，以及触发限流时客户端看到什么。

## 规则 {#rules}

<!-- Planned (guide). Rule fields; must match schema/rate-limit-rule.schema.json. -->

## 键 {#keys}

<!-- Planned (guide). What a rule counts by. -->

## 计数与后端 {#counting-and-backends}

<!-- Planned (guide). Local vs Redis counters. -->

## 响应与响应头 {#responses-and-headers}

<!-- Planned (guide). 429 and rate limit headers. -->

## 故障时的行为 {#failure-behavior}

<!-- Planned (guide). strictFailure; link to security-model#fail-closed. -->
