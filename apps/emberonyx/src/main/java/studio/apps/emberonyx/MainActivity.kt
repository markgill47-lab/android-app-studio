package studio.apps.emberonyx

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
        val prefs = getSharedPreferences("emberonyx", Context.MODE_PRIVATE)

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
                    primary = Palette.brass,
                    onPrimary = Color(0xFF1F1405),
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
                val w = maxWidth
                val h = minOf(maxHeight, w * (8.15f / 7.7f))
                BoardCanvas(m, clock, Modifier.width(w).height(h))
            }
            StatusPanel(m)
            Settings(m)
        }
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
        Canvas(Modifier.size(22.dp)) {
            val r = size.minDimension * 0.34f
            drawPiece(EMBER, size.width * 0.36f, size.height * 0.5f, r)
            drawPiece(ONYX, size.width * 0.68f, size.height * 0.5f, r)
        }
        Text(
            "EMBER & ONYX",
            color = Palette.brass,
            fontSize = 19.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp,
        )
        Spacer(Modifier.weight(1f))
        Pill(if (m.soundOn) "Sound on" else "Sound off", active = m.soundOn) { m.toggleSound() }
        Pill(if (armed) "Tap to quit" else "New game", active = armed) {
            val midGame = m.board.moves > 0 && m.phase != Phase.OVER && m.phase != Phase.CLEARING
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
            .background(if (active) Palette.brass.copy(alpha = 0.16f) else Palette.panel)
            .border(1.dp, if (active) Palette.brass else Palette.panelEdge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) Palette.brass else Palette.text, fontSize = 13.sp, maxLines = 1)
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
        Dot(EMBER)
        Text("  You $w", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(
            "${m.gameDifficulty.label} · $d draw${if (d == 1) "" else "s"}",
            color = Palette.dim,
            fontSize = 13.sp,
        )
        Spacer(Modifier.weight(1f))
        Text("Onyx $l  ", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Dot(ONYX)
    }
}

@Composable
private fun Dot(side: Int) {
    Canvas(Modifier.size(16.dp)) {
        drawPiece(side, size.width / 2, size.height / 2, size.minDimension * 0.48f)
    }
}

@Composable
private fun BoardCanvas(m: Match, clock: MutableLongState, modifier: Modifier) {
    Canvas(
        modifier
            .clipToBounds()
            .pointerInput(m) {
                // Touch anywhere over a column to hold a piece above it,
                // slide to move it, and let go to drop.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val geo = Geo.of(size.width.toFloat(), size.height.toFloat())
                    m.touch(geo.colAt(down.position.x))
                    var lastX = down.position.x
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        lastX = change.position.x
                        change.consume()
                        m.touch(geo.colAt(lastX))
                    }
                    m.release(geo.colAt(lastX))
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
            over && m.winner == EMBER -> Palette.brass
            over && m.winner == ONYX -> Palette.silver
            m.phase == Phase.PLAYER -> Palette.emberLight
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
                colors = ButtonDefaults.buttonColors(containerColor = Palette.brass, contentColor = Color(0xFF1F1405)),
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
