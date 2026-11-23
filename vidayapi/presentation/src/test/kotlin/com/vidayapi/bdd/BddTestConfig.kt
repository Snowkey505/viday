package com.vidayapi.bdd

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper

/**
 * Бины BDD-инфраструктуры. Конфигурация импортируется в
 * [CucumberSpringConfiguration], поэтому не зависит от component-сканирования
 * тестовых классов.
 */
@Configuration(proxyBeanMethods = false)
class BddTestConfig {

    @Bean
    fun scenarioContext(): ScenarioContext = ScenarioContext()

    @Bean
    fun bddSupport(jdbcTemplate: JdbcTemplate): BddSupport = BddSupport(jdbcTemplate)

    @Bean
    fun bddHttpClient(environment: Environment, mapper: ObjectMapper): BddHttpClient =
        BddHttpClient(environment, mapper)
}
