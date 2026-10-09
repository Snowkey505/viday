package com.vidayapi.config

import com.vidayapi.security.RlsAwareDataSource
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.stereotype.Component

@Component
class RlsDataSourcePostProcessor : BeanPostProcessor {
    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (bean is HikariDataSource) {
            bean.connectionInitSql = "RESET ROLE; RESET app.current_user_id;"
            return RlsAwareDataSource(bean)
        }
        return bean
    }
}
