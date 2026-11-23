package com.snowkey.viday.data

import com.snowkey.viday.model.AuthData

interface PreferencesLocalDataSource {
    suspend fun setAuthData(authData: AuthData)
    suspend fun getAuthData(): AuthData?
    suspend fun clearAuthData()
}
