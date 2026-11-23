package com.vidayapi.port

interface CachePort {
    fun get(key: String): String?
    fun set(key: String, value: String, ttlSeconds: Long)
    fun delete(key: String)
    fun deleteByPrefix(prefix: String)
}
