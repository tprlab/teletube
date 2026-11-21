package com.tprlab.teletube

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val lightColors = lightColorScheme(
    primary = Color(0xFF643CAD),
    onPrimary = Color(0xFFFFF9FF),
    primaryContainer = Color(0xFFEEE3FF),
    onPrimaryContainer = Color(0xFF35165F),
    inversePrimary = Color(0xFFD6B9FF),
    secondary = Color(0xFF675478),
    onSecondary = Color(0xFFFFF9FF),
    secondaryContainer = Color(0xFFEDE2FC),
    onSecondaryContainer = Color(0xFF3C2851),
    background = Color(0xFFFAF7FE),
    onBackground = Color(0xFF251D32),
    surface = Color(0xFFFAF7FE),
    onSurface = Color(0xFF251D32),
    onSurfaceVariant = Color(0xFF584C66),
    surfaceVariant = Color(0xFFE9E0F3),
    surfaceContainerLowest = Color(0xFFFDFBFF),
    surfaceContainerLow = Color(0xFFF5EFFC),
    surfaceContainer = Color(0xFFF0E9F9),
    surfaceContainerHigh = Color(0xFFECE4F6),
    surfaceContainerHighest = Color(0xFFE6DDF2),
    surfaceTint = Color(0xFF643CAD),
    inverseSurface = Color(0xFF382E45),
    inverseOnSurface = Color(0xFFF8F0FD),
    outline = Color(0xFF80728F),
    outlineVariant = Color(0xFFD8CDE4),
)

private val darkColors = darkColorScheme(
    primary = Color(0xFFD6B9FF),
    onPrimary = Color(0xFF39186A),
    primaryContainer = Color(0xFF4D2D7B),
    onPrimaryContainer = Color(0xFFF0E4FF),
    inversePrimary = Color(0xFF643CAD),
    secondary = Color(0xFFD2BDE6),
    onSecondary = Color(0xFF39284B),
    secondaryContainer = Color(0xFF432F58),
    onSecondaryContainer = Color(0xFFF0E4FF),
    background = Color(0xFF17121F),
    onBackground = Color(0xFFF2EAFB),
    surface = Color(0xFF17121F),
    onSurface = Color(0xFFF2EAFB),
    onSurfaceVariant = Color(0xFFCCC0DB),
    surfaceVariant = Color(0xFF41364E),
    surfaceContainerLowest = Color(0xFF120D1A),
    surfaceContainerLow = Color(0xFF1E1728),
    surfaceContainer = Color(0xFF261E33),
    surfaceContainerHigh = Color(0xFF2D243C),
    surfaceContainerHighest = Color(0xFF382D48),
    surfaceTint = Color(0xFFD6B9FF),
    inverseSurface = Color(0xFFEDE3F7),
    inverseOnSurface = Color(0xFF33283F),
    outline = Color(0xFF9D8EAC),
    outlineVariant = Color(0xFF4B3E5B),
)

@Composable
internal fun TeleTubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColors else lightColors, content = content)
}

@Composable
internal fun TeleTubeMark(size: Dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(percent = 26),
        color = colorResource(R.color.teletube_logo_background),
    ) {
        Image(
            painter = painterResource(R.drawable.teletube_mark),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
