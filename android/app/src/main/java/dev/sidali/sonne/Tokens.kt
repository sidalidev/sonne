package dev.sidali.sonne

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * The one place for colors, shapes, type, springs and the shared components.
 * Sonne has its own color, honey (#E9A23B), the same in the icon and in the app, in light and dark.
 * Everything is flat (no gradient, no sheen) and rounded (no hard corner). Screens never pick a raw
 * color: they ask Tokens.colors for a role.
 */
object Tokens {
    val cardRadius = 22.dp
    val cardPadding = 20.dp
    val buttonRadius = 14.dp
    val buttonHeight = 54.dp
    val secondaryHeight = 50.dp
    const val pressedScale = 0.97f
    /** Press feedback: quick and slightly bouncy, interruptible. */
    const val pressDamping = 0.55f
    const val pressStiffness = 900f
    /** Predictive back: how far the screen shrinks and slides while the back swipe is held. */
    const val backScale = 0.9f
    val backShift = 24.dp
    /** Line drawings: 24 unit grid, 2.1 thick, round caps and joins. */
    const val strokeGrid = 24f
    const val strokeWidth = 2.1f

    /** Titles: Bricolage Grotesque, bold, letter spacing -2 %. */
    val titleFont = FontFamily(Font(R.font.bricolage_grotesque_bold, FontWeight.Bold))
    val titleSpacing: TextUnit = (-0.02).em

    /** Body: Figtree, a variable font, one entry per weight the app uses. */
    @OptIn(ExperimentalTextApi::class)
    val bodyFont = FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { w ->
            Font(R.font.figtree, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))
        },
    )

    /** The roles the screens use. */
    val colors: Roles
        @Composable @ReadOnlyComposable get() = LocalRoles.current
}

/** What each color is for. */
@Immutable
class Roles(
    /** Page behind the cards. */
    val page: Color,
    /** Cards and the tab bar. */
    val card: Color,
    /** One step above a card: capsules, empty gauge bars, slider track. */
    val raised: Color,
    val hairline: Color,
    val text: Color,
    val secondaryText: Color,
    /** Honey: the one filled action per screen, the hero. Never used for small text. */
    val accent: Color,
    val onAccent: Color,
    /** Honey dark enough (light theme) or light enough (dark theme) for text: links, selected tab, back. */
    val accentInk: Color,
    /** Secondary action tinted with honey. */
    val tint: Color,
    val onTint: Color,
    /** Connected, checked, switches. */
    val success: Color,
    val onSuccess: Color,
    /** Things that need attention. */
    val warning: Color,
    /** Stop ringing, change key, errors. */
    val danger: Color,
    val onDanger: Color,
    val dangerSoft: Color,
    val onDangerSoft: Color,
    /** Update banner. */
    val notice: Color,
    val onNotice: Color,
    /** Hero and the ringing screen: flat honey, ink on it. */
    val hero: Color,
    val onHero: Color,
    /** Hero when Sonne is off. */
    val heroIdle: Color,
    val onHeroIdle: Color,
    /** Nearby gauge, cold to very hot. */
    val heat: List<Color>,
    /** QR codes are read on white, whatever the theme. */
    val qrPaper: Color,
)

private val LocalRoles = staticCompositionLocalOf<Roles> { error("ThemeSonne is missing") }

/**
 * The family palette, "Miel du Champsaur". With the paper ink below, these are the only literal colors in
 * the app (plus the launch window and the icon in res/). Every text pair is at least 4.5:1 (WCAG AA),
 * computed: ink on honey 6.9, honey ink on cards 5.9 (light) and 7.9 (dark), secondary text 7.3 and 6.5.
 */
private object Palette {
    val honey = Color(0xFFE9A23B)
    val ink = Color(0xFF1F2A22)
    val cream = Color(0xFFFBF7EF)

    val light = Roles(
        page = cream,
        card = Color(0xFFFFFFFF),
        raised = Color(0xFFF4ECDD),
        hairline = Color(0xFFE8DFCF),
        text = ink,
        secondaryText = Color(0xFF4A5A4F),
        accent = honey, onAccent = ink,
        accentInk = Color(0xFF8A5A12),
        tint = Color(0xFFF4E3C3), onTint = Color(0xFF6B4A12),
        success = Color(0xFF256B42), onSuccess = cream,
        warning = Color(0xFF9A4A00),
        danger = Color(0xFFB3261E), onDanger = Color(0xFFFFFFFF),
        dangerSoft = Color(0xFFFBE0DB), onDangerSoft = Color(0xFF5F1712),
        notice = Color(0xFFF4E3C3), onNotice = Color(0xFF4A3209),
        hero = honey, onHero = ink,
        heroIdle = Color(0xFFF4ECDD), onHeroIdle = Color(0xFF4A5A4F),
        heat = listOf(Color(0xFF2E7FA1), Color(0xFF3E8E6A), honey, Color(0xFFE07A2E), Color(0xFFC8402F)),
        qrPaper = Color(0xFFFFFFFF),
    )

    val dark = Roles(
        page = Color(0xFF12201A),
        card = Color(0xFF1C2E26),
        raised = Color(0xFF26392F),
        hairline = Color(0xFF2E4238),
        text = Color(0xFFEEF2EC),
        secondaryText = Color(0xFF9FB3A8),
        accent = honey, onAccent = ink,
        accentInk = Color(0xFFF0B75E),
        tint = Color(0xFF3A3122), onTint = Color(0xFFF4D9A6),
        success = Color(0xFF6FD39A), onSuccess = Color(0xFF12201A),
        warning = Color(0xFFFFB86B),
        danger = Color(0xFFFFB4AB), onDanger = Color(0xFF690005),
        dangerSoft = Color(0xFF4A1E1A), onDangerSoft = Color(0xFFFFDAD5),
        notice = Color(0xFF3A3122), onNotice = Color(0xFFF4E3C3),
        hero = honey, onHero = ink,
        heroIdle = Color(0xFF26392F), onHeroIdle = Color(0xFF9FB3A8),
        heat = listOf(Color(0xFF5BB0D3), Color(0xFF6FD39A), honey, Color(0xFFF08A3E), Color(0xFFFF7A66)),
        qrPaper = Color(0xFFFFFFFF),
    )

    /** Material 3 roles for the few Material components (dialogs, text fields, sliders), from the same palette. */
    fun scheme(r: Roles, dark: Boolean) = if (dark) {
        darkColorScheme(
            primary = r.accentInk, onPrimary = ink,
            primaryContainer = honey, onPrimaryContainer = ink,
            secondary = r.secondaryText, onSecondary = r.page,
            secondaryContainer = r.tint, onSecondaryContainer = r.onTint,
            tertiary = r.success, onTertiary = r.onSuccess,
            error = r.danger, onError = r.onDanger,
            errorContainer = r.dangerSoft, onErrorContainer = r.onDangerSoft,
            background = r.page, onBackground = r.text,
            surface = r.page, onSurface = r.text,
            surfaceVariant = r.raised, onSurfaceVariant = r.secondaryText,
            surfaceTint = Color.Transparent,
            inverseSurface = cream, inverseOnSurface = ink, inversePrimary = Color(0xFF8A5A12),
            outline = Color(0xFF6E8277), outlineVariant = r.hairline, scrim = Color(0xFF000000),
            surfaceBright = r.raised, surfaceDim = r.page,
            surfaceContainerLowest = r.page, surfaceContainerLow = r.card,
            surfaceContainer = r.card, surfaceContainerHigh = r.card,
            surfaceContainerHighest = r.raised,
        )
    } else {
        lightColorScheme(
            primary = r.accentInk, onPrimary = cream,
            primaryContainer = honey, onPrimaryContainer = ink,
            secondary = r.secondaryText, onSecondary = cream,
            secondaryContainer = r.tint, onSecondaryContainer = r.onTint,
            tertiary = r.success, onTertiary = r.onSuccess,
            error = r.danger, onError = r.onDanger,
            errorContainer = r.dangerSoft, onErrorContainer = r.onDangerSoft,
            background = r.page, onBackground = r.text,
            surface = r.page, onSurface = r.text,
            surfaceVariant = r.raised, onSurfaceVariant = r.secondaryText,
            surfaceTint = Color.Transparent,
            inverseSurface = ink, inverseOnSurface = cream, inversePrimary = Color(0xFFF0B75E),
            outline = Color(0xFF7A867D), outlineVariant = r.hairline, scrim = Color(0xFF000000),
            surfaceBright = Color(0xFFFFFFFF), surfaceDim = r.raised,
            surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = r.page,
            surfaceContainer = r.card, surfaceContainerHigh = r.card,
            surfaceContainerHighest = r.raised,
        )
    }
}

@Composable
fun ThemeSonne(contenu: @Composable () -> Unit) {
    val sombre = isSystemInDarkTheme()
    val r = if (sombre) Palette.dark else Palette.light
    val schema = remember(sombre) { Palette.scheme(r, sombre) }
    val corps = Tokens.bodyFont
    val titres = Tokens.titleFont
    val esp = Tokens.titleSpacing
    // Sizes in sp: the system text size is respected.
    val type = remember {
        val base = Typography()
        base.copy(
            displayLarge = base.displayLarge.copy(fontFamily = titres, fontWeight = FontWeight.Bold, letterSpacing = esp),
            displayMedium = base.displayMedium.copy(fontFamily = titres, fontWeight = FontWeight.Bold, letterSpacing = esp),
            displaySmall = base.displaySmall.copy(fontFamily = titres, fontWeight = FontWeight.Bold, letterSpacing = esp),
            headlineLarge = TextStyle(fontFamily = titres, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = esp, lineHeight = 40.sp),
            headlineMedium = TextStyle(fontFamily = titres, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = esp, lineHeight = 34.sp),
            headlineSmall = TextStyle(fontFamily = titres, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = esp, lineHeight = 30.sp),
            titleLarge = TextStyle(fontFamily = titres, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = esp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontFamily = corps, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
            titleSmall = TextStyle(fontFamily = corps, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
            bodyLarge = TextStyle(fontFamily = corps, fontSize = 17.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = corps, fontSize = 15.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontFamily = corps, fontSize = 13.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontFamily = corps, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = base.labelMedium.copy(fontFamily = corps),
            labelSmall = base.labelSmall.copy(fontFamily = corps),
        )
    }
    // Rounded everywhere: cards 22, dialogs 28, fields 14.
    val formes = Shapes(
        extraSmall = RoundedCornerShape(Tokens.buttonRadius), small = RoundedCornerShape(Tokens.buttonRadius),
        medium = RoundedCornerShape(Tokens.cardRadius), large = RoundedCornerShape(Tokens.cardRadius),
        extraLarge = RoundedCornerShape(28.dp),
    )
    CompositionLocalProvider(LocalRoles provides r) {
        MaterialTheme(colorScheme = schema, typography = type, shapes = formes) {
            androidx.compose.material3.Surface(color = r.page, contentColor = r.text) { contenu() }
        }
    }
}

/** Sonne's bell, the same drawing as the app icon (24 unit grid, round caps and joins). */
internal object Cloche {
    val corps: Path = PathParser().parsePathString("M6.5 16v-4.5a5.5 5.5 0 0 1 11 0V16l1.3 1.6a.6.6 0 0 1-.5 1H5.7a.6.6 0 0 1-.5-1z").toPath()
    val battant: Path = PathParser().parsePathString("M10.2 20.6a2 2 0 0 0 3.6 0").toPath()
}

/** Draws the bell centered in a [cote] square at [haut]-[gauche], stroke scaled with it. */
internal fun DrawScope.dessinerCloche(couleur: Color, cote: Float, gauche: Float = 0f, haut: Float = 0f) {
    val u = cote / Tokens.strokeGrid
    val trait = Stroke(Tokens.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    translate(gauche, haut) {
        withTransform({ scale(u, u, Offset.Zero) }) {
            drawPath(Cloche.corps, couleur, style = trait)
            drawPath(Cloche.battant, couleur, style = trait)
        }
    }
}

/**
 * Apple-like press: shrinks to 0.97 on a spring from the first frame, no Material ripple, and a light
 * tick through the view (so the system "touch feedback" setting is respected).
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    haptic: Int = HapticFeedbackConstants.CLOCK_TICK,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val view = LocalView.current
    val s by animateFloatAsState(
        if (pressed) Tokens.pressedScale else 1f,
        spring(dampingRatio = Tokens.pressDamping, stiffness = Tokens.pressStiffness),
        label = "press",
    )
    this
        .graphicsLayer { scaleX = s; scaleY = s; alpha = if (pressed) 0.85f else 1f }
        .clickable(interactionSource = src, indication = null, enabled = enabled, onClickLabel = onClickLabel, role = role) {
            view.performHapticFeedback(haptic)
            onClick()
        }
}

/** Rounded card, no shadow. */
@Composable
internal fun Carte(modifier: Modifier = Modifier, contenu: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Tokens.cardRadius))
            .background(Tokens.colors.card)
            .padding(Tokens.cardPadding),
        content = contenu,
    )
}

/** Small uppercase section label above a group of cards. */
@Composable
internal fun Section(texte: String) {
    Text(
        texte.uppercase(), fontSize = 12.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.SemiBold,
        color = Tokens.colors.secondaryText, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

/** Large filled action: one per screen. */
@Composable
internal fun BoutonPrincipal(
    texte: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    couleur: Color = Tokens.colors.accent,
    surCouleur: Color = Tokens.colors.onAccent,
    actif: Boolean = true,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(Tokens.buttonHeight)
            .pressable(enabled = actif, haptic = HapticFeedbackConstants.CONFIRM, onClick = onClick)
            .clip(RoundedCornerShape(Tokens.buttonRadius))
            .background(if (actif) couleur else couleur.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) { Text(texte, color = surCouleur, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

/**
 * Tinted secondary action: honey tint with dark honey text by default, or 12 % of [couleur]
 * (success, warning, danger) with the color itself as text.
 */
@Composable
internal fun BoutonSecondaire(texte: String, onClick: () -> Unit, modifier: Modifier = Modifier, couleur: Color? = null) {
    val fond = couleur?.copy(alpha = 0.12f) ?: Tokens.colors.tint
    val encre = couleur ?: Tokens.colors.onTint
    Box(
        modifier
            .fillMaxWidth()
            .height(Tokens.secondaryHeight)
            .pressable(onClick = onClick)
            .clip(RoundedCornerShape(Tokens.buttonRadius))
            .background(fond),
        contentAlignment = Alignment.Center,
    ) { Text(texte, color = encre, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

/** Small capsule button, for actions inside a row. */
@Composable
internal fun BoutonCapsule(texte: String, onClick: () -> Unit, couleur: Color = Tokens.colors.accentInk) {
    Box(
        Modifier
            .pressable(onClick = onClick)
            .clip(RoundedCornerShape(50))
            .background(Tokens.colors.raised)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) { Text(texte, color = couleur, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

/** Material filled buttons (dialogs, banners): honey with ink text, like [BoutonPrincipal]. */
@Composable
internal fun couleursBouton() = ButtonDefaults.buttonColors(
    containerColor = Tokens.colors.accent, contentColor = Tokens.colors.onAccent,
    disabledContainerColor = Tokens.colors.accent.copy(alpha = 0.35f), disabledContentColor = Tokens.colors.onAccent.copy(alpha = 0.6f),
)

/** Green switch, with a tick as it flips. */
@Composable
internal fun Interrupteur(valeur: Boolean, onChange: (Boolean) -> Unit) {
    val view = LocalView.current
    Switch(
        checked = valeur,
        onCheckedChange = {
            view.performHapticFeedback(
                when {
                    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HapticFeedbackConstants.CLOCK_TICK
                    it -> HapticFeedbackConstants.TOGGLE_ON
                    else -> HapticFeedbackConstants.TOGGLE_OFF
                },
            )
            onChange(it)
        },
        colors = SwitchDefaults.colors(
            checkedTrackColor = Tokens.colors.success, checkedThumbColor = Tokens.colors.onSuccess,
            checkedIconColor = Tokens.colors.success, checkedBorderColor = Color.Transparent,
        ),
    )
}

/** Status dot, pulsing when [vivant]. */
@Composable
internal fun Pastille(couleur: Color, vivant: Boolean) {
    val t = rememberInfiniteTransition(label = "pastille")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(10.dp).clip(CircleShape).background(if (vivant) couleur.copy(alpha = a) else couleur))
}

/** Row with a check or a capsule action, used for permissions and setup steps. */
@Composable
internal fun Coche(taille: Int = 28) {
    val fond = Tokens.colors.success
    val trait = Tokens.colors.onSuccess
    Canvas(Modifier.size(taille.dp)) {
        drawCircle(fond)
        val u = size.minDimension / 28f
        val coche = Path().apply { moveTo(8.5f * u, 14.5f * u); lineTo(12.5f * u, 18.5f * u); lineTo(19.5f * u, 10 * u) }
        drawPath(coche, trait, style = Stroke(2.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}


/**
 * Hero: Sonne's bell on a flat honey disc, sound waves pulsing out, the bell swinging while it rings.
 * Calm and muted when [actif] is false.
 */
@Composable
internal fun HeroTelephone(actif: Boolean, sonne: Boolean, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "hero").animateFloat(
        0f, 1f, infiniteRepeatable(tween(if (sonne) 900 else 2600, easing = LinearEasing)), label = "t",
    )
    val c = Tokens.colors
    val onde = c.accent
    val disque = if (actif) c.hero else c.heroIdle
    val trait = if (actif) c.onHero else c.onHeroIdle
    Canvas(modifier) {
        val u = size.minDimension / 220f
        val centre = Offset(size.width / 2, size.height / 2)
        if (actif) for (i in 0..2) {
            val p = ((t + i / 3f) % 1f)
            val r = (64 + p * 44) * u
            drawCircle(onde.copy(alpha = (1 - p) * if (sonne) 0.6f else 0.3f), r, centre, style = Stroke(3 * u, cap = StrokeCap.Round))
        }
        drawCircle(disque, 62 * u, centre)
        // Bell, swinging a few degrees from its top while it rings.
        val angle = if (sonne) (kotlin.math.sin(t * 2 * Math.PI * 4) * 9).toFloat() else 0f
        val cote = 64 * u
        withTransform({ rotate(angle, Offset(centre.x, centre.y - cote * 0.35f)) }) {
            dessinerCloche(trait, cote, centre.x - cote / 2, centre.y - cote / 2)
        }
    }
}

/** Ink for paper and codes: fixed, not themed. The rescue kit is printed and QR codes are scanned dark on light. */
object Encre {
    val noir = 0xFF000000.toInt()
    val blanc = 0xFFFFFFFF.toInt()
    val gris = 0xFF6C6C70.toInt()
    val grisClair = 0xFF8E8E93.toInt()
}
