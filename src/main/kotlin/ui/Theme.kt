package ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
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

// "Bench" palette: quiet graphite chrome, warm chalk text, one amber signal color
// reserved for connection state, selection and primary actions.
object Bench {
    val Graphite = Color(0xFF1B1C20)
    val Panel = Color(0xFF232429)
    val Raised = Color(0xFF2B2D33)
    val Rule = Color(0xFF3A3C44)
    val RuleSoft = Color(0xFF2F3137)
    val Chalk = Color(0xFFE6E3DA)
    val Slate = Color(0xFF9B9EA8)
    val Signal = Color(0xFFF5A524)

    /** Recessed well behind streamed output (logcat, shell). */
    val Well = Color(0xFF151619)
}

val AppDarkColorScheme = darkColorScheme(
    primary = Bench.Signal,
    onPrimary = Color(0xFF2A1C00),
    primaryContainer = Color(0xFF3D2E12),
    onPrimaryContainer = Color(0xFFFFD89A),
    secondary = Color(0xFF6FD49A),
    onSecondary = Color(0xFF00391B),
    secondaryContainer = Color(0xFF193828),
    onSecondaryContainer = Color(0xFFBDF0D2),
    tertiary = Color(0xFF7FB0FF),
    onTertiary = Color(0xFF00264A),
    tertiaryContainer = Color(0xFF1C2F4D),
    onTertiaryContainer = Color(0xFFD3E3FF),
    background = Bench.Graphite,
    onBackground = Bench.Chalk,
    surface = Bench.Panel,
    onSurface = Bench.Chalk,
    surfaceVariant = Bench.Raised,
    onSurfaceVariant = Bench.Slate,
    surfaceContainerLowest = Bench.Well,
    surfaceContainerLow = Bench.Graphite,
    surfaceContainer = Bench.Panel,
    surfaceContainerHigh = Bench.Raised,
    surfaceContainerHighest = Color(0xFF34363D),
    outline = Bench.Rule,
    outlineVariant = Bench.RuleSoft,
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF4A0006),
    errorContainer = Color(0xFF4A1C1E),
    onErrorContainer = Color(0xFFFFD3D1),
    inverseSurface = Bench.Chalk,
    inverseOnSurface = Bench.Graphite,
    inversePrimary = Color(0xFF8A5A00),
    scrim = Color(0xFF000000),
    surfaceTint = Color.Transparent,
)

/** Logcat priority colors; F is split from E so fatal lines stand out. */
object LogLevelColors {
    val Verbose = Color(0xFF7C808A)
    val Debug = Color(0xFF7FB0FF)
    val Info = Color(0xFF6FD49A)
    val Warn = Color(0xFFEBCB5C)
    val Error = Color(0xFFFF6B6B)
    val Fatal = Color(0xFFF07BD6)
    val Default = Bench.Chalk
}

/** Hover highlight for list rows. */
val HoverColor = Color(0xFF30323A)

// Radius encodes hierarchy: controls 4, panels 6, menus 8, dialogs 10.
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(10.dp),
)

/** JetBrains Mono for everything technical (paths, packages, serials, logs). */
val AppMonoFamily: FontFamily = runCatching {
    FontFamily(
        Font(resource = "fonts/JetBrainsMono-Regular.ttf", weight = FontWeight.Normal),
        Font(resource = "fonts/JetBrainsMono-Medium.ttf", weight = FontWeight.Medium),
        Font(resource = "fonts/JetBrainsMono-Bold.ttf", weight = FontWeight.Bold),
    )
}.getOrDefault(FontFamily.Monospace)

// Dense desktop scale (11/12/13/15/18), system sans for UI text.
val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = titleSmall.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyLarge = bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = 0.sp),
        bodySmall = bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.sp),
        labelLarge = labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        labelMedium = labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        labelSmall = labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    )
}

/** Hairline used on cards that sit on the same tone as their parent. */
@Composable
fun appCardBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

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

/** Shared empty state: muted icon, title, subtitle. */
@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
