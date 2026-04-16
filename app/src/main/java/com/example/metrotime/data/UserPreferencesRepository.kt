package com.example.metrotime.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val LOGGED_IN_USER_EMAIL = stringPreferencesKey("logged_in_user_email")
        // Storing passwords directly in DataStore is not secure for a real app.
        // This is for demonstration purposes only.
        fun userPasswordKey(email: String) = stringPreferencesKey("password_$email")
    }

    val loggedInUserEmail: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[Keys.LOGGED_IN_USER_EMAIL]
        }

    suspend fun getPasswordForEmail(email: String): String? {
        return context.dataStore.data.map { preferences ->
            preferences[Keys.userPasswordKey(email)]
        }.first()
    }

    suspend fun saveNewUser(email: String, password: String) {
        context.dataStore.edit {
            // Save the password for the new user
            it[Keys.userPasswordKey(email)] = password
        }
    }
    
suspend fun loginUser(email: String) {
        context.dataStore.edit {
            it[Keys.LOGGED_IN_USER_EMAIL] = email
        }
    }

    suspend fun logoutUser() {
        context.dataStore.edit {
            it.remove(Keys.LOGGED_IN_USER_EMAIL)
        }
    }
}
