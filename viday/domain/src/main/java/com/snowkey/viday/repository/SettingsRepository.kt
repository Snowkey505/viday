package com.snowkey.viday.repository

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val themeFlow: Flow<Boolean>
    suspend fun setTheme(isDark: Boolean)
}
