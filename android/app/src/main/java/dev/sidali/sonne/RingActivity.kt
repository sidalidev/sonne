package dev.sidali.sonne

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.ui.draw.clip
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.sp

/** Écran de sonnerie : plein écran, au-dessus du verrouillage, un seul gros bouton Stop. */
class RingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Honey in both themes: dark system bar icons, always.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val sonne by Etat.sonne.collectAsState()
            LaunchedEffect(sonne) { if (!sonne) finish() }
            ThemeSonne { EcranSonnerie(onStop = { Ringer.arreter(this) }) }
        }
    }
}

/** Flat honey, ink on it: it has to be seen across a room, in light and dark alike. */
@Composable
private fun EcranSonnerie(onStop: () -> Unit) {
    val c = Tokens.colors
    val encre = c.onHero
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.hero)
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.app_name),
            color = encre,
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = Tokens.titleFont,
            letterSpacing = Tokens.titleSpacing,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.sonnerie_texte),
            color = encre,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))
        val t by rememberInfiniteTransition(label = "ondes").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "t",
        )
        Box(Modifier.size(320.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                for (i in 0..2) {
                    val p = (t + i / 3f) % 1f
                    drawCircle(encre.copy(alpha = (1 - p) * 0.45f), size.minDimension / 2 * (0.65f + 0.35f * p), style = Stroke(4.dp.toPx()))
                }
            }
            Box(
                Modifier
                    .size(200.dp)
                    .pressable(haptic = HapticFeedbackConstants.CONFIRM, onClick = onStop)
                    .clip(CircleShape)
                    .background(encre),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.stop), color = c.hero, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                    fontFamily = Tokens.titleFont, letterSpacing = Tokens.titleSpacing,
                )
            }
        }
    }
}
