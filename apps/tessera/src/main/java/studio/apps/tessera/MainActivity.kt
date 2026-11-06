package studio.apps.tessera

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var sounds: Sounds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sounds = Sounds(applicationContext)
        val prefs = getSharedPreferences("tessera", Context.MODE_PRIVATE)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        volumeControlStream = AudioManager.STREAM_MUSIC

        setContent {
            val scope = rememberCoroutineScope()
            val match = remember { Match(sounds, prefs, scope) }
            DisposableEffect(match) { onDispose { match.dispose() } }
            val haptics = LocalHapticFeedback.current
            match.onImpact = { strong ->
                haptics.performHapticFeedback(
                    if (strong) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove,
                )
            }
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Palette.sun,
                    onPrimary = Color(0xFF241303),
                    background = Palette.bgBottom,
                    surface = Palette.panel,
                ),
            ) {
                GameScreen(match)
            }
        }
    }

    override fun onDestroy() {
        sounds.release()
        super.onDestroy()
    }
}

@Composable
private fun GameScreen(m: Match) {
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        m.start()
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                // A long gap (app paused) is not a long stretch of animation.
                if (last != 0L) m.step(((now - last) / 1e9f).coerceIn(0f, 0.05f))
                last = now
                clock.longValue = now
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.bgTop, Palette.bgBottom))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            TopBar(m)
            ScoreRow(m, m.version)
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val side = minOf(maxWidth, maxHeight)
                BoardCanvas(m, clock, Modifier.size(side))
            }
            StatusPanel(m)
            Settings(m)
        }
        if (m.showRules) RulesCard(m)
    }
}

@Composable
private fun RulesCard(m: Match) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC05060B))
            .clickable { m.closeRules() },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier
                .padding(24.dp)
                .clip(shape)
                .background(Palette.panel)
                .border(1.dp, Palette.gold.copy(alpha = 0.6f), shape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("HOW TO PLAY", color = Palette.gold, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            Rule("1", "The big board is nine small boards. Win a small board, three in a row, to claim that square.")
            Rule("2", "Claim three squares in a row on the big board to win the game.")
            Rule("3", "Where you play sends Jade to the matching board. Play the top-right square of any board, and Jade must answer in the top-right board.")
            Rule("4", "If you are sent to a board that is already decided, play in any open board. A drawn board counts for nobody.")
            Text("The glowing frame always shows where the next move goes.", color = Palette.dim, fontSize = 13.sp)
            Button(
                onClick = { m.closeRules() },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.sun, contentColor = Color(0xFF241303)),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text("Got it", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Rule(n: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(n, color = Palette.sun, fontSize = 15.sp, fontWeight = FontWeight.Black)
        Text(text, color = Palette.text, fontSize = 14.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun TopBar(m: Match) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(2500)
            armed = false
        }
    }
    Row(
        Modifier.fillMaxWidth().height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(Modifier.size(24.dp)) {
            val r = size.minDimension * 0.2f
            drawCross(Offset(size.width * 0.3f, size.height * 0.5f), r, 1f, 1f, r * 0.45f, glow = false)
            drawRing(Offset(size.width * 0.72f, size.height * 0.5f), r, 1f, 1f, r * 0.45f, glow = false)
        }
        Text(
            "TESSERA",
            color = Palette.gold,
            fontSize = 19.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.weight(1f))
        Pill("?", active = false) { m.openRules() }
        Pill(if (m.soundOn) "Sound" else "Muted", active = m.soundOn) { m.toggleSound() }
        Pill(if (armed) "Tap to quit" else "New game", active = armed) {
            val midGame = m.game.moves > 0 && m.phase != Phase.OVER && m.phase != Phase.CLEARING
            if (armed || !midGame) {
                armed = false
                m.newGame()
            } else {
                armed = true
            }
        }
    }
}

@Composable
private fun Pill(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (active) Palette.gold.copy(alpha = 0.16f) else Palette.panel)
            .border(1.dp, if (active) Palette.gold else Palette.panelEdge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) Palette.gold else Palette.text, fontSize = 13.sp, maxLines = 1)
    }
}

// Your record against the level being played: wins, draws, losses.
@Composable
private fun ScoreRow(m: Match, version: Int) {
    check(version >= 0)
    val (w, d, l) = m.record(m.gameDifficulty)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(SUN)
        Text("  You $w", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(
            "${m.gameDifficulty.label} · $d draw${if (d == 1) "" else "s"}",
            color = Palette.dim,
            fontSize = 13.sp,
        )
        Spacer(Modifier.weight(1f))
        Text("Jade $l  ", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Dot(JADE)
    }
}

@Composable
private fun Dot(side: Int) {
    Canvas(Modifier.size(16.dp)) {
        val r = size.minDimension * 0.36f
        drawMark(side, Offset(size.width / 2, size.height / 2), r, 1f, 1f, r * 0.42f, glow = false)
    }
}

@Composable
private fun BoardCanvas(m: Match, clock: MutableLongState, modifier: Modifier) {
    Canvas(
        modifier
            .clipToBounds()
            .pointerInput(m) {
                detectTapGestures { pos ->
                    val geo = Geo.of(size.width.toFloat(), size.height.toFloat())
                    m.tap(geo.hit(pos.x, pos.y))
                }
            },
    ) {
        if (clock.longValue < 0L) return@Canvas
        drawBoard(m, Geo.of(size.width, size.height))
    }
}

@Composable
private fun StatusPanel(m: Match) {
    val over = m.phase == Phase.OVER
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val color = when {
            over && m.game.result == SUN -> Palette.sunLight
            over && m.game.result == JADE -> Palette.jadeLight
            m.phase == Phase.PLAYER -> Palette.sunLight
            m.phase == Phase.THINKING -> Palette.jadeLight
            else -> Palette.text
        }
        Text(
            m.status,
            color = color,
            fontSize = if (over) 17.sp else 15.sp,
            fontWeight = if (over) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
        )
        if (over) {
            Button(
                onClick = { m.newGame() },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.sun, contentColor = Color(0xFF241303)),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text("Play again", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Settings(m: Match) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Label("OPPONENT")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (d in Difficulty.entries) {
                Pill(d.label, active = d == m.difficulty, Modifier.weight(1f)) { m.chooseDifficulty(d) }
            }
        }
        val note = if (m.difficulty != m.gameDifficulty) {
            "${m.difficulty.label} starts with the next game."
        } else {
            m.difficulty.blurb
        }
        Text(note, color = Palette.dim, fontSize = 12.sp)
        Label("FIRST MOVE")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in Starter.entries) {
                Pill(s.label, active = s == m.starter, Modifier.weight(1f)) { m.chooseStarter(s) }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Palette.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
}
