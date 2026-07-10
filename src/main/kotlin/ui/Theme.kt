package ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// GitHub-dark surfaces, Android-green accent
val AppDarkColorScheme = darkColorScheme(
    primary = Color(0xFF3DDC84),
    onPrimary = Color(0xFF00391B),
    primaryContainer = Color(0xFF10402A),
    onPrimaryContainer = Color(0xFFA9F5C6),
    secondary = Color(0xFF56D364),
    onSecondary = Color(0xFF003914),
    secondaryContainer = Color(0xFF00531F),
    onSecondaryContainer = Color(0xFFACF0BD),
    tertiary = Color(0xFF79B8FF),
    onTertiary = Color(0xFF00264A),
    tertiaryContainer = Color(0xFF003A6E),
    onTertiaryContainer = Color(0xFFCEE5FF),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1C2128),
    onSurfaceVariant = Color(0xFF8B949E),
    outline = Color(0xFF30363D),
    outlineVariant = Color(0xFF21262D),
    error = Color(0xFFFF7B72),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF67060C),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE6EDF3),
    inverseOnSurface = Color(0xFF0D1117),
    inversePrimary = Color(0xFF1F8A50),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF3DDC84),
)

/** Hover highlight for list rows. */
val HoverColor = Color(0xFF262C36)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** JetBrains Mono for everything technical (paths, packages, serials, logs). */
val AppMonoFamily: FontFamily = runCatching {
    FontFamily(
        Font(resource = "fonts/JetBrainsMono-Regular.ttf", weight = FontWeight.Normal),
        Font(resource = "fonts/JetBrainsMono-Medium.ttf", weight = FontWeight.Medium),
        Font(resource = "fonts/JetBrainsMono-Bold.ttf", weight = FontWeight.Bold),
    )
}.getOrDefault(FontFamily.Monospace)

val AppTypography = Typography().run {
    copy(
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Bold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(letterSpacing = 0.4.sp),
    )
}

/** 1px low-alpha outline used on all cards. */
@Composable
fun appCardBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))

/** Interaction source + hovered state for desktop hover highlights. */
@Composable
fun rememberHover(): Pair<MutableInteractionSource, State<Boolean>> {
    val source = remember { MutableInteractionSource() }
    return source to source.collectIsHoveredAsState()
}

/**
 * Fades/expands content in and out based on a nullable value,
 * keeping the last non-null value visible during the exit animation.
 */
@Composable
fun <T : Any> AnimatedFade(value: T?, content: @Composable (T) -> Unit) {
    var lastValue by remember { mutableStateOf(value) }
    if (value != null) lastValue = value
    AnimatedVisibility(
        visible = value != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        lastValue?.let { content(it) }
    }
}

/** Shared empty state: tinted icon badge, title, subtitle. */
@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}
