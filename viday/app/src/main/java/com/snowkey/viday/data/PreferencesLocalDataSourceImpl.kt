package com.snowkey.viday.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.snowkey.viday.model.AuthData
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PreferencesLocalDataSourceImpl(
    private val dataStore: DataStore<Preferences>
) : PreferencesLocalDataSource {
    private val authKey = stringPreferencesKey("auth_data")

    override suspend fun setAuthData(authData: AuthData) {
        dataStore.edit { preferences ->
            preferences[authKey] = Json.encodeToString(authData)
        }
    }

    override suspend fun getAuthData(): AuthData? {
        val json = dataStore.data.first()[authKey]
        return if (json != null) Json.decodeFromString(json) else null
    }

    override suspend fun clearAuthData() {
        dataStore.edit { preferences ->
            preferences.remove(authKey)
        }
    }
}
