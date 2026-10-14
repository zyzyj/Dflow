package com.zyzyj.dflow.ui.theme

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

/**
 * Material You theming.
 *
 * On Android 12+ the palette is derived from the user's wallpaper, so the app
 * looks native on their device rather than carrying its own fixed brand colours.
 * Below that, a hand-tuned fallback keeps the same relationships (a cool accent
 * against a deep neutral surface) so both paths read as the same app.
 */

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFA8C7FF),
    onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF1B4A7E),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBFC6DC),
    onSecondary = Color(0xFF293042),
    secondaryContainer = Color(0xFF3F4759),
    onSecondaryContainer = Color(0xFFDBE2F9),
    tertiary = Color(0xFFDCBCE0),
    onTertiary = Color(0xFF3E2844),
    tertiaryContainer = Color(0xFF563E5C),
    onTertiaryContainer = Color(0xFFF9D8FC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
)

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF3B608F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FF),
    onPrimaryContainer = Color(0xFF001C39),
    secondary = Color(0xFF565E71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131B2C),
    tertiary = Color(0xFF715573),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBD7FB),
    onTertiaryContainer = Color(0xFF29132D),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF74777F),
)

@Composable
fun DflowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Disable to inspect the fallback palette on a modern device. */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> FallbackDark
        else -> FallbackLight
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
