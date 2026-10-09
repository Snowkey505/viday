package com.vidayapi.config

import com.vidayapi.security.RlsContext
import com.vidayapi.security.RlsContextHolder
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

@Configuration(proxyBeanMethods = false)
class ReferenceDataInitializer {
    @Bean
    fun referenceDataRunner(jdbcTemplate: JdbcTemplate): ApplicationRunner {
        return ApplicationRunner {
            RlsContextHolder.withContext(RlsContext.ADMIN) {
                jdbcTemplate.update(
                    """
                    INSERT INTO viday.role(id, name)
                    VALUES (1, 'GUEST'), (2, 'USER'), (3, 'CREATOR'), (4, 'ADMIN'), (5, 'ANALYST')
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent()
                )

                jdbcTemplate.update(
                    """
                    INSERT INTO viday.access_type(id, name)
                    VALUES (1, 'PUBLIC'), (2, 'PRIVATE'), (3, 'FOLLOWERS')
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent()
                )

                jdbcTemplate.update(
                    """
                    INSERT INTO viday.content_type(id, name)
                    VALUES (1, 'VIDEO'), (2, 'STREAM')
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent()
                )

                jdbcTemplate.update(
                    """
                    INSERT INTO viday.stream_status(id, name)
                    VALUES (1, 'SCHEDULED'), (2, 'LIVE'), (3, 'ENDED'), (4, 'ERROR')
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent()
                )
            }
        }
    }
}
