package me.ahoo.cosec.redis

import me.ahoo.cache.Cache
import me.ahoo.cosec.Delegated

class RolePermissionCache(override val delegate: Cache<String, Set<String>>) :
    Cache<String, Set<String>> by delegate,
    Delegated<Cache<String, Set<String>>>
