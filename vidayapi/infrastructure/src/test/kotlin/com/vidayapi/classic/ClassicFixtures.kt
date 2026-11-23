package com.vidayapi.classic

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance

private val BUSINESS_TABLES = listOf(
    "user",
    "user_follows",
    "playlist",
    "content",
    "video",
    "media_variant",
    "content_to_playlist",
)

fun ClassicJdbc.assertRepositoriesEmpty() {
    BUSINESS_TABLES.forEach { table ->
        assertThat(dataSource.catalog.rows(table))
            .describedAs("table '$table' must be empty after fixture cleanup")
            .isEmpty()
    }
}

interface PerTestJdbcFixture {
    val db: ClassicJdbc

    @BeforeEach
    fun wipeRepositoriesBeforeTest() {
        db.reset()
    }

    @AfterEach
    fun wipeAndVerifyRepositoriesAfterTest() {
        db.reset()
        db.assertRepositoriesEmpty()
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
interface SuiteJdbcFixture {
    val db: ClassicJdbc

    @BeforeAll
    fun wipeRepositoriesBeforeAllTests() {
        db.reset()
        db.assertRepositoriesEmpty()
    }

    @AfterAll
    fun wipeAndVerifyRepositoriesAfterAllTests() {
        db.reset()
        db.assertRepositoriesEmpty()
    }
}
