package com.laurio.rangelog.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF2F5D46),
    secondary = Color(0xFF8A5A1E),
    tertiary = Color(0xFFB3261E),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9CD3B4),
    secondary = Color(0xFFF2B872),
    tertiary = Color(0xFFF2B8B5),
)

@Composable
fun RangeLogTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, content = content)
}
