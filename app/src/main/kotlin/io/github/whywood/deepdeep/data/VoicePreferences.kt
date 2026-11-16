package io.github.whywood.deepdeep.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.voiceStore by preferencesDataStore(name = "voice")

class VoicePreferences(context: Context) {
    private val store = context.applicationContext.voiceStore
    private val autoSpeakKey = booleanPreferencesKey("auto_speak")
    private val callCaptionsKey = booleanPreferencesKey("voice_call_captions")
    private val callInterruptKey = floatPreferencesKey("voice_call_interrupt")
    private val voiceNameKey = stringPreferencesKey("voice_name")

    val autoSpeak: Flow<Boolean> = store.data
        .map { values -> values[autoSpeakKey] ?: true }
        .catch { error -> if (error is IOException) emit(true) else throw error }

    val voiceCallShowCaptions: Flow<Boolean> = store.data
        .map { values -> values[callCaptionsKey] ?: true }
        .catch { error -> if (error is IOException) emit(true) else throw error }

    val voiceCallInterrupt: Flow<Float> = store.data
        .map { values -> values[callInterruptKey] ?: 0.6f }
        .catch { error -> if (error is IOException) emit(0.6f) else throw error }

    val voiceName: Flow<String> = store.data
        .map { values -> values[voiceNameKey] ?: "" }
        .catch { error -> if (error is IOException) emit("") else throw error }

    suspend fun setAutoSpeak(enabled: Boolean) {
        store.edit { it[autoSpeakKey] = enabled }
    }

    suspend fun setVoiceCallShowCaptions(enabled: Boolean) {
        store.edit { it[callCaptionsKey] = enabled }
    }

    suspend fun setVoiceCallInterrupt(threshold: Float) {
        store.edit { it[callInterruptKey] = threshold.coerceIn(0.3f, 0.9f) }
    }

    suspend fun setVoiceName(name: String) {
        store.edit { it[voiceNameKey] = name }
    }
}
