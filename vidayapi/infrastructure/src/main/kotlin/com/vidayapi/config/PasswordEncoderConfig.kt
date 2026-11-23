package com.vidayapi.config

import com.vidayapi.port.PasswordEncoder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

@Configuration(proxyBeanMethods = false)
class PasswordEncoderConfig {
    @Bean
    fun passwordEncoder(): PasswordEncoder {
        val bcrypt = BCryptPasswordEncoder()
        return object : PasswordEncoder {
            override fun encode(rawPassword: String): String = bcrypt.encode(rawPassword)
            override fun matches(rawPassword: String, encodedPassword: String): Boolean =
                bcrypt.matches(rawPassword, encodedPassword)
        }
    }
}
