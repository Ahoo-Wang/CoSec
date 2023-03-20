package me.ahoo.cosec.redis

import me.ahoo.cache.Cache
import me.ahoo.cosec.Delegated
import me.ahoo.cosec.api.permission.AppPermission

class AppPermissionCache(override val delegate: Cache<String, AppPermission>) :
    Cache<String, AppPermission> by delegate,
    Delegated<Cache<String, AppPermission>>
