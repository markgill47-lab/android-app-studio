package studio.apps.swappuzzle

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var game: Game

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        game = Game(getSharedPreferences("swappuzzle", Context.MODE_PRIVATE))
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
                    App(game, Modifier.padding(insets))
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        game.pause()
    }

    override fun onResume() {
        super.onResume()
        game.resume()
    }
}

@Composable
fun App(game: Game, modifier: Modifier = Modifier) {
    when (game.screen) {
        Screen.SETUP -> SetupScreen(game, modifier)
        Screen.PLAY -> {
            BackHandler { game.quit() }
            PlayScreen(game, modifier)
        }
    }
}

@Composable
fun SetupScreen(game: Game, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Swap Puzzle", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "A row of icons is hidden. Swap icons in your row until it matches. " +
                "After each swap you only learn how many are in the right place.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Text("How many icons?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in MIN_SIZE..MAX_SIZE) {
                val chosen = n == game.size
                val buttonModifier = Modifier.size(48.dp)
                val padding = PaddingValues(0.dp)
                if (chosen) {
                    Button(onClick = { game.chooseSize(n) }, modifier = buttonModifier, contentPadding = padding) {
                        Text("$n")
                    }
                } else {
                    OutlinedButton(onClick = { game.chooseSize(n) }, modifier = buttonModifier, contentPadding = padding) {
                        Text("$n")
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        val best = game.best(game.size)
        Text(
            if (best.swaps == null) {
                "No best yet for ${game.size} icons"
            } else {
                "Best for ${game.size}: ${best.swaps} swaps, ${formatTime(best.timeMs ?: 0L)}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = { game.start() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Start", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun PlayScreen(game: Game, modifier: Modifier = Modifier) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(game.solved, game.hidden) {
        while (!game.solved) {
            elapsed = game.elapsedMs()
            delay(250)
        }
        elapsed = game.elapsedMs()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val slot = min(72.dp, (maxWidth - 32.dp) / game.size)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { game.quit() }) { Text("Quit") }
                Spacer(Modifier.weight(1f))
                Stat("Swaps", "${game.swaps}")
                Spacer(Modifier.size(24.dp))
                Stat("Time", formatTime(elapsed))
            }

            Spacer(Modifier.height(24.dp))
            Label(if (game.solved) "The hidden row" else "Hidden row")
            Row {
                game.hidden.forEach { glyph ->
                    GlyphTile(if (game.solved) glyph else null, slot, highlighted = false, Modifier.size(slot))
                }
            }

            Spacer(Modifier.height(28.dp))
            Label("Your row")
            PlayerRow(game, slot)

            Spacer(Modifier.height(28.dp))
            Text(
                "${game.correct} of ${game.size} right",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            val change = game.lastChange
            Text(
                when {
                    change == null -> "Drag an icon onto another, or tap two icons, to swap them"
                    change > 0 -> "Up $change from the last swap"
                    change < 0 -> "Down ${-change} from the last swap"
                    else -> "No change from the last swap"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            if (game.solved) {
                Spacer(Modifier.height(24.dp))
                SolvedCard(game, elapsed)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SolvedCard(game: Game, elapsed: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Solved!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("${game.swaps} swaps" + if (game.newBestSwaps) "  (new best)" else "")
            Text(formatTime(elapsed) + if (game.newBestTime) "  (new best)" else "")
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { game.quit() }) { Text("Change size") }
                Button(onClick = { game.start() }) { Text("Play again") }
            }
        }
    }
}

// The player's row. Drag a tile onto another slot to swap them, or tap one
// tile and then another.
@Composable
private fun PlayerRow(game: Game, slot: Dp) {
    val n = game.size
    val slotPx = with(LocalDensity.current) { slot.toPx() }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val currentGame by rememberUpdatedState(game)

    fun indexAt(x: Float): Int = (x / slotPx).toInt().coerceIn(0, n - 1)

    val dropTarget = dragFrom?.let { indexAt(dragStart.x + dragOffset.x) }

    Box(
        modifier = Modifier
            .size(slot * n, slot)
            .pointerInput(n, slotPx) {
                detectTapGestures { pos -> currentGame.tap(indexAt(pos.x)) }
            }
            .pointerInput(n, slotPx) {
                detectDragGestures(
                    onDragStart = { pos ->
                        if (!currentGame.solved) {
                            dragFrom = indexAt(pos.x)
                            dragStart = pos
                            dragOffset = Offset.Zero
                        }
                    },
                    onDrag = { change, amount ->
                        if (dragFrom != null) {
                            change.consume()
                            dragOffset += amount
                        }
                    },
                    onDragEnd = {
                        val from = dragFrom
                        if (from != null) currentGame.swap(from, indexAt(dragStart.x + dragOffset.x))
                        dragFrom = null
                        dragOffset = Offset.Zero
                    },
                    onDragCancel = {
                        dragFrom = null
                        dragOffset = Offset.Zero
                    },
                )
            },
    ) {
        game.row.forEachIndexed { i, glyph ->
            val dragging = dragFrom == i
            GlyphTile(
                glyph = glyph,
                slot = slot,
                highlighted = game.selected == i || (dropTarget == i && !dragging),
                modifier = Modifier
                    .offset(x = slot * i)
                    .size(slot)
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        if (dragging) {
                            translationX = dragOffset.x
                            translationY = dragOffset.y
                            scaleX = 1.12f
                            scaleY = 1.12f
                        }
                    },
            )
        }
    }
}

// One tile. A null glyph draws as a hidden "?" tile.
@Composable
private fun GlyphTile(glyph: Glyph?, slot: Dp, highlighted: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(slot * 0.18f)
    val borderColor = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = modifier
            .padding(slot * 0.05f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(if (highlighted) 3.dp else 1.dp, borderColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (glyph == null) {
            val fontSize = with(LocalDensity.current) { (slot * 0.45f).toSp() }
            Text("?", fontSize = fontSize, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Canvas(modifier = Modifier.fillMaxSize().padding(slot * 0.14f)) {
                drawGlyph(glyph)
            }
        }
    }
}
