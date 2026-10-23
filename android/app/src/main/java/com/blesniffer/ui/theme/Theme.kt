package com.blesniffer.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5E9C),
    secondary = Color(0xFF4A6572),
    tertiary = Color(0xFF00696E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CCAFF),
    secondary = Color(0xFFB4CAD6),
    tertiary = Color(0xFF4EDADF),
)

/** Signal strength and CRC colours that read the same in both schemes. */
object SnifferColors {
    val crcError: Color
        @Composable get() = MaterialTheme.colorScheme.error

    /** Green channel badge, meaning the packet's CRC checked out. */
    val channelOkBackground: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF1E4620) else Color(0xFFD3ECD4)

    val channelOkText: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF9BDBA0) else Color(0xFF1B5E20)

    /** Green Start button. Stop keeps the theme's error colour. */
    val startContainer: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF6FBF73) else Color(0xFF2E7D32)

    val onStartContainer: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF06280C) else Color.White

    /** Background for a repaired payload, so it never reads as received data. */
    val repairedBackground: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2A3A2C) else Color(0xFFE4F0E5)

    /** Grey badge used for neutral counters such as the session total. */
    val neutralBadgeBackground: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF34383C) else Color(0xFFE3E5E8)

    val neutralBadgeText: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFFC6CACE) else Color(0xFF44484C)

    @Composable
    fun forRssi(rssi: Int): Color = when {
        rssi >= -60 -> if (isSystemInDarkTheme()) Color(0xFF7DD87D) else Color(0xFF1B7F1B)
        rssi >= -80 -> if (isSystemInDarkTheme()) Color(0xFFE8C46A) else Color(0xFF8A6100)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
fun BleSnifferTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    // The app draws edge to edge, so the status and navigation bars sit over its
    // own background. Without telling the system which way round the contrast
    // runs, the clock and the navigation buttons come out light-on-light and
    // effectively disappear.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(colorScheme = colors, content = content)
}
