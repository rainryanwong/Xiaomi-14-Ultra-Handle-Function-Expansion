package com.handlefix.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.handlefix.ModeConfig
import org.json.JSONObject
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Stored in the existing JSON, so configuration backup/import includes the UI preference. */
internal object ThemePreference {
    var mode by mutableIntStateOf(ModeConfig.read()?.optInt("themeMode", 0)?.takeIf { it in 0..2 } ?: 0)
    fun sync(config: JSONObject?) {
        mode = config?.optInt("themeMode", 0)?.takeIf { it in 0..2 } ?: 0
    }
}

@Composable
internal fun useDarkTheme(): Boolean = when (ThemePreference.mode) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}

/** One preference drives every Activity and its system bar icon contrast. */
@Composable
fun HandleFixTheme(content: @Composable () -> Unit) {
    val colors = if (useDarkTheme()) darkColorScheme() else lightColorScheme()
    MiuixTheme(colors = colors, content = content)
}
