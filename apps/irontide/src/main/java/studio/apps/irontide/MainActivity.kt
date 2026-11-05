package studio.apps.irontide

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
        val prefs = getSharedPreferences("irontide", Context.MODE_PRIVATE)

        // Full screen: the two oceans want every pixel of a portrait phone.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        volumeControlStream = AudioManager.STREAM_MUSIC

        setContent {
            val scope = rememberCoroutineScope()
            val battle = remember { Battle(sounds, prefs, scope) }
            val haptics = LocalHapticFeedback.current
            battle.onHit = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Palette.amber,
                    onPrimary = Color(0xFF1A1206),
                    background = Palette.bg,
                    surface = Palette.panel,
                ),
            ) {
                GameScreen(battle)
            }
        }
    }

    override fun onDestroy() {
        sounds.release()
        super.onDestroy()
    }
}

@Composable
private fun GameScreen(b: Battle) {
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                // A long gap (app paused) is not a long stretch of animation.
                if (last != 0L) b.step(((now - last) / 1e9f).coerceIn(0f, 0.05f))
                last = now
                clock.longValue = now
            }
        }
    }
    val version = b.version
    val setup = b.phase == Phase.SETUP

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            TopBar(b)
            SectionLabel("YOUR FLEET") {
                Tally(version) { t -> b.engine.home.ship(t)?.let { b.engine.home.isSunk(it) } == true }
            }
            BoxWithConstraints(
                Modifier.weight(if (setup) 1f else 0.8f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                FleetGrid(b, clock, Modifier.size(minOf(maxWidth, maxHeight)))
            }
            if (setup) {
                SetupPanel(b, version)
            } else {
                StatusBar(b)
                SectionLabel("ENEMY WATERS") {
                    Tally(version) { t -> b.engine.playerIntel.sunkShips.any { it.type == t } }
                }
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    TargetGrid(b, clock, Modifier.size(minOf(maxWidth, maxHeight)))
                }
            }
        }
        if (b.showResult) ResultCard(b, version)
    }
}

@Composable
private fun TopBar(b: Battle) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(2500)
            armed = false
        }
    }
    Row(
        Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "IRON TIDE",
            color = Palette.amber,
            fontSize = 21.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 4.sp,
        )
        Spacer(Modifier.weight(1f))
        if (b.phase != Phase.SETUP) {
            Text(b.difficulty.label, color = Palette.dim, fontSize = 13.sp)
        }
        Pill(if (b.soundOn) "Sound on" else "Sound off", active = b.soundOn) { b.toggleSound() }
        if (b.phase != Phase.SETUP) {
            Pill(if (armed) "Tap to quit" else "New game", active = armed) {
                if (armed || b.phase == Phase.OVER) {
                    armed = false
                    b.newGame()
                } else {
                    armed = true
                }
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
            .background(if (active) Palette.amber.copy(alpha = 0.16f) else Palette.panel)
            .border(1.dp, if (active) Palette.amber else Palette.panelEdge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) Palette.amber else Palette.text, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun SectionLabel(title: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Palette.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

// The five ships of one side, dimmed and charred as they go down.
@Composable
private fun Tally(version: Int, sunk: (ShipType) -> Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (type in ShipType.entries) {
            val gone = version >= 0 && sunk(type)
            Canvas(Modifier.size(width = (type.length * 7).dp, height = 9.dp).alpha(if (gone) 0.5f else 1f)) {
                drawHullIcon(type, if (gone) HullStyle.WRECK else HullStyle.AFLOAT)
            }
        }
    }
}

@Composable
private fun StatusBar(b: Battle) {
    Row(
        Modifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            b.status,
            color = Palette.text,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val (label, color) = when {
            b.phase == Phase.PLAYER -> "YOUR TURN" to Palette.amber
            b.enemyActing -> "ENEMY FIRING" to Palette.red
            else -> null to Palette.dim
        }
        if (label != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(color.copy(alpha = 0.18f))
                    .border(1.dp, color, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun FleetGrid(b: Battle, clock: MutableLongState, modifier: Modifier) {
    Canvas(
        modifier.pointerInput(b) {
            // Setup gestures: a tap places or turns a ship, a drag moves one.
            awaitEachGesture {
                val down = awaitFirstDown()
                if (b.phase != Phase.SETUP) return@awaitEachGesture
                val geo = Geo.of(size.width.toFloat(), size.height.toFloat())
                val start = geo.cellAt(down.position)
                var dragging = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!dragging && start != null) b.tapSetup(start)
                            break
                        }
                        val moved = (change.position - down.position).getDistance()
                        if (!dragging && start != null && moved > viewConfiguration.touchSlop) {
                            dragging = b.beginDrag(start)
                        }
                        if (dragging) {
                            change.consume()
                            b.moveDrag(geo.cellAtClamped(change.position))
                        }
                    }
                } finally {
                    if (dragging) b.endDrag()
                }
            }
        },
    ) {
        if (clock.longValue < 0L) return@Canvas
        val geo = Geo.of(size.width, size.height)
        val fx = b.homeFx
        val t = fx.time
        val j = fx.jitter()
        translate(j.x * geo.cell, j.y * geo.cell) {
            drawOcean(geo, t, null)
            val fleet = b.engine.home
            for (ship in fleet.ships) {
                drawShip(geo, ship, if (fleet.isSunk(ship)) HullStyle.WRECK else HullStyle.AFLOAT)
            }
            for (cell in Cell.all) {
                if (!fleet.wasShot(cell)) continue
                val ship = fleet.shipAt(cell)
                if (ship == null) drawMiss(geo, cell, t)
                else drawFire(geo, cell, t, if (fleet.isSunk(ship)) 0.6f else 1f)
            }
            b.drag?.let { drawShip(geo, it.ship, if (it.valid) HullStyle.GHOST_OK else HullStyle.GHOST_BAD) }
            b.rejected?.let { drawShip(geo, it, HullStyle.GHOST_BAD) }
            b.lastShotHome?.let { outline(geo, it) }
            fx.draw(this, geo)
        }
    }
}

@Composable
private fun TargetGrid(b: Battle, clock: MutableLongState, modifier: Modifier) {
    Canvas(
        modifier.pointerInput(b) {
            detectTapGestures { p ->
                Geo.of(size.width.toFloat(), size.height.toFloat()).cellAt(p)?.let { b.fire(it) }
            }
        },
    ) {
        if (clock.longValue < 0L) return@Canvas
        val geo = Geo.of(size.width, size.height)
        val fx = b.enemyFx
        val t = fx.time
        val j = fx.jitter()
        val intel = b.engine.playerIntel
        translate(j.x * geo.cell, j.y * geo.cell) {
            drawOcean(geo, t, if (b.phase == Phase.PLAYER) Palette.amber else null)
            for (ship in intel.sunkShips) drawShip(geo, ship, HullStyle.WRECK)
            // When the battle is lost, show where the rest of their fleet was.
            if (b.phase == Phase.OVER) {
                for (ship in b.engine.enemy.ships) {
                    if (intel.sunkShips.none { it.type == ship.type }) drawShip(geo, ship, HullStyle.REVEALED)
                }
            }
            for (cell in Cell.all) {
                when (intel.mark(cell)) {
                    Mark.MISS -> drawMiss(geo, cell, t)
                    Mark.HIT -> drawFire(geo, cell, t, if (intel.sunk[cell.index]) 0.6f else 1f)
                    Mark.UNKNOWN -> Unit
                }
            }
            b.lastShotEnemy?.let { outline(geo, it) }
            fx.draw(this, geo)
            if (b.enemyActing) {
                drawRect(
                    Color.Black, Offset(geo.left, geo.top), Size(geo.cell * SIZE, geo.cell * SIZE), alpha = 0.22f,
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.outline(geo: Geo, cell: Cell) {
    val c = geo.cell
    drawRect(
        Palette.amber,
        Offset(geo.left + cell.col * c + c * 0.04f, geo.top + cell.row * c + c * 0.04f),
        Size(c * 0.92f, c * 0.92f),
        alpha = 0.7f,
        style = Stroke(width = c * 0.05f),
    )
}

@Composable
private fun SetupPanel(b: Battle, version: Int) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val selected = b.selected
        val hint = when {
            selected != null ->
                "Tap a cell to place your ${selected.label} (${selected.length}). " +
                    "Tap a placed ship to turn it, or drag it to move it."
            b.ready -> "Fleet ready. Drag ships to adjust them, or start the battle."
            else -> "Pick a ship below to place it."
        }
        Text(hint, color = Palette.dim, fontSize = 13.sp, minLines = 2)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (type in ShipType.entries) {
                ShipChip(type, placed = version >= 0 && b.isPlaced(type), chosen = type == selected, Modifier.weight(1f)) {
                    b.select(type)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (b.horizontal) "Across ↔" else "Down ↕", active = false, Modifier.weight(1f)) { b.rotateNext() }
            Pill("Shuffle", active = false, Modifier.weight(1f)) { b.randomFleet() }
            Pill("Clear", active = false, Modifier.weight(1f)) { b.clearFleet() }
        }

        Text("ENEMY ADMIRAL", color = Palette.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (d in Difficulty.entries) {
                Pill(d.label, active = d == b.difficulty, Modifier.weight(1f)) { b.chooseDifficulty(d) }
            }
        }
        Text(b.difficulty.blurb, color = Palette.dim, fontSize = 12.sp)

        Button(
            onClick = { b.start() },
            enabled = version >= 0 && b.ready,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.amber, contentColor = Color(0xFF1A1206)),
        ) {
            Text("Start battle", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ShipChip(type: ShipType, placed: Boolean, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (chosen) Palette.amber.copy(alpha = 0.16f) else Palette.panel)
            .border(1.dp, if (chosen) Palette.amber else Palette.panelEdge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(12.dp).alpha(if (placed) 0.35f else 1f)) {
            drawHullIcon(type, HullStyle.AFLOAT)
        }
        Text(
            type.label,
            color = if (chosen) Palette.amber else Palette.text,
            fontSize = 10.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        Text(if (placed) "placed" else "${type.length} cells", color = Palette.dim, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun ResultCard(b: Battle, version: Int) {
    // Read so the card recomposes as the boards change underneath it.
    check(version >= 0)
    val won = b.engine.playerWon
    val mine = b.engine.playerIntel
    val theirs = b.engine.enemyIntel
    val accuracy = if (mine.shots > 0) mine.hits * 100 / mine.shots else 0
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(28.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Palette.panel)
                .border(1.dp, if (won) Palette.amber else Palette.red, RoundedCornerShape(18.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (won) "VICTORY" else "DEFEAT",
                color = if (won) Palette.amber else Palette.red,
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 6.sp,
            )
            Text(
                if (won) "You sent the enemy fleet to the bottom." else "The enemy sank your whole fleet.",
                color = Palette.text,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                "You fired ${mine.shots} shots, ${mine.hits} hits ($accuracy%).",
                color = Palette.dim,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                "The ${b.difficulty.label.lowercase()} admiral fired ${theirs.shots} shots, ${theirs.hits} hits.",
                color = Palette.dim,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = { b.newGame() },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.amber, contentColor = Color(0xFF1A1206)),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text("Play again", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { b.showResult = false }) {
                Text("Look at the boards", color = Palette.dim)
            }
        }
    }
}
