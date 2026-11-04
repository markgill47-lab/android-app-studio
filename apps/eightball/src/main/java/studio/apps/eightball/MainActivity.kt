package studio.apps.eightball

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.exp

class MainActivity : ComponentActivity() {
    private lateinit var sensors: SensorManager
    private val motion = Motion()
    private val die = Die(Icosahedron.faces)
    private lateinit var renderer: Renderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val prefs = getSharedPreferences("eightball", Context.MODE_PRIVATE)
        renderer = Renderer(Icosahedron.faces, savedBall(prefs))

        // Full screen and awake: the whole display is the window into the
        // ball, and it should not lock while it sits face down.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent { BallView(die, renderer, motion, prefs) }
    }

    override fun onResume() {
        super.onResume()
        motion.start(sensors)
    }

    override fun onPause() {
        motion.stop(sensors)
        super.onPause()
    }
}

private const val BALL_KEY = "ball"

private fun savedBall(prefs: SharedPreferences): Ball =
    Ball.entries.firstOrNull { it.name == prefs.getString(BALL_KEY, null) } ?: Ball.BLUE

@Composable
private fun BallView(die: Die, renderer: Renderer, motion: Motion, prefs: SharedPreferences) {
    var frame by remember { mutableLongStateOf(0L) }
    var selected by remember { mutableStateOf(renderer.ball) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    // A long gap (app paused) is not a long fall.
                    val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                    die.step(dt, motion.up(), motion.linear)
                }
                // A new ball takes the old one's place only once the old
                // one has sunk out of sight.
                if (renderer.ball != selected && die.hidden) renderer.ball = selected
                last = now
                frame = now
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures { die.nudge() } },
        ) {
            // Reading the frame counter here redraws the canvas every frame.
            if (frame >= 0L) {
                drawIntoCanvas { renderer.draw(it.nativeCanvas, size.width, size.height, die) }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            for (ball in Ball.entries) {
                BallIcon(ball, chosen = ball == selected) {
                    if (ball != selected) {
                        selected = ball
                        prefs.edit().putString(BALL_KEY, ball.name).apply()
                        if (!die.hidden) die.sink()
                    }
                }
            }
        }
    }
}

// A small round window with the ball's colored triangle in it.
@Composable
private fun BallIcon(ball: Ball, chosen: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xFF0E1022))
            .border(
                width = if (chosen) 2.dp else 1.dp,
                color = if (chosen) Color(0xFFE8ECFF) else Color(0xFF3A3F5C),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val tri = Path().apply {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height * 0.87f)
                lineTo(0f, size.height * 0.87f)
                close()
            }
            drawPath(tri, Color(ball.face).copy(alpha = if (chosen) 1f else 0.55f))
        }
    }
}

// The phone's motion from its sensors. Uses the fused gravity and linear
// acceleration sensors when the phone has them, and otherwise splits the raw
// accelerometer into a slow part (gravity) and a fast part (shaking).
// With no sensor at all it acts as if the phone lies face up and still.
private class Motion : SensorEventListener {
    private var gravity = Vec3(0f, 0f, SensorManager.GRAVITY_EARTH)
    var linear = Vec3.ZERO
        private set
    private var fused = false
    private var lastRawNanos = 0L

    fun up(): Vec3 = gravity.normalized().let { if (it == Vec3.ZERO) Vec3(0f, 0f, 1f) else it }

    fun start(sensors: SensorManager) {
        val g = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val l = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        fused = g != null && l != null
        if (fused) {
            sensors.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME)
            sensors.registerListener(this, l, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        lastRawNanos = 0L
    }

    fun stop(sensors: SensorManager) {
        sensors.unregisterListener(this)
        linear = Vec3.ZERO
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = Vec3(event.values[0], event.values[1], event.values[2])
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> gravity = v
            Sensor.TYPE_LINEAR_ACCELERATION -> linear = v
            Sensor.TYPE_ACCELEROMETER -> {
                if (lastRawNanos == 0L) {
                    gravity = v
                } else {
                    val dt = ((event.timestamp - lastRawNanos) / 1e9f).coerceIn(0f, 0.1f)
                    val keep = exp(-dt / 0.25f)
                    gravity = gravity * keep + v * (1f - keep)
                }
                lastRawNanos = event.timestamp
                linear = v - gravity
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
