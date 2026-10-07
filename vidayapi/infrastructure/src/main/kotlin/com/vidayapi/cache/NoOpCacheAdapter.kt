package com.vidayapi.cache

import com.vidayapi.port.CachePort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "viday.cache", name = ["enabled"], havingValue = "false")
class NoOpCacheAdapter : CachePort {
    override fun get(key: String): String? = null
    override fun set(key: String, value: String, ttlSeconds: Long) = Unit
    override fun delete(key: String) = Unit
    override fun deleteByPrefix(prefix: String) = Unit
}
