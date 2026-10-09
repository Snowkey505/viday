package com.vidayapi.cache

import com.vidayapi.port.CachePort
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
@ConditionalOnProperty(prefix = "viday.cache", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class RedisCacheAdapter(
    private val redisTemplate: StringRedisTemplate,
) : CachePort {

    override fun get(key: String): String? = redisTemplate.opsForValue().get(key)

    override fun set(key: String, value: String, ttlSeconds: Long) {
        redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS)
    }

    override fun delete(key: String) {
        redisTemplate.delete(key)
    }

    override fun deleteByPrefix(prefix: String) {
        val keys = redisTemplate.keys("$prefix*")
        if (!keys.isNullOrEmpty()) {
            redisTemplate.delete(keys)
        }
    }
}
