package dev.vis.ark.ui

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import android.view.View
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/** Position (px from the centre), rotation (degrees, clockwise) and their velocities. */
private class LogoBody {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var angle by mutableFloatStateOf(0f)
    var scale by mutableFloatStateOf(1f)
    var vx = 0f
    var vy = 0f
    var spin = 0f // deg/s
    /** Pushed off its spot in the middle: stays wherever it ends up, and shaking the phone moves it. */
    var loose by mutableStateOf(false)
    /** Shake force from the accelerometer, already in screen px/s² (the "in your phone" pseudo-force). */
    @Volatile var ax = 0f
    @Volatile var ay = 0f
}

/**
 * The Blank homepage's logo as a little physical toy, like Arc's: grab it anywhere and it hangs off your
 * finger (turning around the grab point), throw it and it flies, bounces off the edges and spins, with
 * haptics for the grab, the throw, every bounce (harder hits, stronger thuds) and fast spins. Once it's been
 * moved it stays loose like a little thing inside the phone: shake the phone and it rattles around. Double-tap
 * it (or drop it back in the middle) to pin it again. A tap, on the logo or anywhere else, still opens search.
 */
@Composable
fun BoxScope.ThrowableLogo(onTap: () -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    val body = remember { LogoBody() }
    val haptics = remember(view) { ToyHaptics(view) }
    val scope = rememberCoroutineScope()
    val sim = remember { arrayOfNulls<Job>(1) }
    val radius = with(density) { 44.dp.toPx() }  // the artwork (two cards) inside the 180dp image
    val grabRadius = with(density) { 70.dp.toPx() }
    val pinRadius = with(density) { 28.dp.toPx() }
    val area = remember { floatArrayOf(0f, 0f) }
    val pendingTap = remember { arrayOfNulls<Job>(1) }
    val lastLogoTap = remember { longArrayOf(0L) }

    fun launchFly() {
        if (area[0] <= 0f || sim[0]?.isActive == true) return
        sim[0] = scope.launch {
            fly(body, haptics, radius, area[0], area[1])
            // Came to rest close to the middle: snap back onto its spot and pin it there.
            if (hypot(body.x, body.y) < pinRadius) { body.loose = false; settle(body, haptics) }
        }
    }
    fun pinHome() {
        sim[0]?.cancel()
        body.loose = false
        sim[0] = scope.launch { settle(body, haptics) }
    }

    // Shake: while the logo is loose, the phone's own movement pushes it the other way (it "stays put" while the
    // walls move), so a flick of the phone slams it into a wall. Gravity is filtered out, so tilting does nothing.
    if (body.loose) {
        val ctx = LocalContext.current
        val pxPerMeter = with(density) { 2600.dp.toPx() }
        DisposableEffect(Unit) {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            // The fused linear-acceleration sensor already has gravity taken out; without one, filter the raw
            // accelerometer (a slow low-pass is the gravity estimate, what's left is the shake).
            val linear = sm?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            val sensor = linear ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gravity = FloatArray(3)
            var primed = linear != null
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    val v = e.values
                    if (!primed) { v.copyInto(gravity, 0, 0, 3); primed = true; return }
                    if (linear == null) for (i in 0..2) gravity[i] = gravity[i] * 0.94f + v[i] * 0.06f
                    var lx = v[0] - gravity[0]
                    var ly = v[1] - gravity[1]
                    // Hand tremor and sensor noise stay below ~1.5 m/s²; only real shakes count.
                    val mag = hypot(lx, ly)
                    if (mag < 1.5f) { body.ax = 0f; body.ay = 0f; return }
                    val k = (mag - 1.5f) / mag
                    lx *= k; ly *= k
                    // Device axes (x right, y up) → screen axes, for the current display rotation.
                    val rot = view.display?.rotation ?: Surface.ROTATION_0
                    val (sx, sUp) = when (rot) {
                        Surface.ROTATION_90 -> ly to -lx
                        Surface.ROTATION_180 -> -lx to -ly
                        Surface.ROTATION_270 -> -ly to lx
                        else -> lx to ly
                    }
                    // The logo is pushed opposite to the phone's acceleration (screen y points down).
                    body.ax = -sx * pxPerMeter
                    body.ay = sUp * pxPerMeter
                    view.post { launchFly() }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            if (sensor != null) sm?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            onDispose { sm?.unregisterListener(listener); body.ax = 0f; body.ay = 0f }
        }
    }

    Box(
        Modifier
            .matchParentSize()
            .onSizeChanged { area[0] = it.width.toFloat(); area[1] = it.height.toFloat() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val centre = Offset(size.width / 2f + body.x, size.height / 2f + body.y)
                    var r = down.position - centre
                    val onLogo = r.getDistance() < grabRadius
                    if (onLogo) {
                        sim[0]?.cancel()
                        pendingTap[0]?.cancel()
                        body.vx = 0f; body.vy = 0f; body.spin = 0f
                        haptics.grab()
                        scope.launch { animate(body.scale, 1.08f, animationSpec = spring(0.5f, Spring.StiffnessMedium)) { v, _ -> body.scale = v } }
                    }
                    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    // Recent (time, angle) samples, for the spin you let go with.
                    val angles = ArrayDeque<Pair<Long, Float>>().apply { add(down.uptimeMillis to body.angle) }
                    var moved = false
                    var lastTick = body.angle
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        if (!moved && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (!moved || !onLogo) continue
                        ch.consume()
                        body.loose = true
                        // Pulling at r by d turns the logo by the torque r × d (plus some inertia so a grab near
                        // the middle mostly slides it); the grab point stays under the finger.
                        val d = ch.positionChange()
                        val inertia = radius * radius * 0.8f
                        val dTheta = (r.x * d.y - r.y * d.x) / (r.getDistanceSquared() + inertia)
                        r = rotate(r, dTheta)
                        body.angle += (dTheta * 180f / PI).toFloat()
                        val c = ch.position - r
                        body.x = c.x - size.width / 2f
                        body.y = c.y - size.height / 2f
                        tracker.addPosition(ch.uptimeMillis, ch.position)
                        angles.addLast(ch.uptimeMillis to body.angle)
                        while (angles.size > 2 && ch.uptimeMillis - angles.first().first > 90) angles.removeFirst()
                        if (abs(body.angle - lastTick) >= 45f) { haptics.spinTick(0.35f); lastTick = body.angle }
                    }
                    if (!onLogo) {
                        if (!moved) onTap()
                        return@awaitEachGesture
                    }
                    scope.launch { animate(body.scale, 1f, animationSpec = spring(0.45f, Spring.StiffnessMedium)) { v, _ -> body.scale = v } }
                    if (!moved) {
                        haptics.release(0.2f)
                        if (!body.loose) { onTap(); pinHome(); return@awaitEachGesture }
                        // Loose: a double-tap calls it home, so a single tap waits a moment before searching.
                        val now = System.currentTimeMillis()
                        if (now - lastLogoTap[0] < viewConfiguration.doubleTapTimeoutMillis) {
                            lastLogoTap[0] = 0L
                            pinHome()
                        } else {
                            lastLogoTap[0] = now
                            launchFly()
                            pendingTap[0] = scope.launch { delay(viewConfiguration.doubleTapTimeoutMillis); onTap() }
                        }
                        return@awaitEachGesture
                    }
                    val v = tracker.calculateVelocity()
                    val maxV = 9000f
                    body.vx = v.x.coerceIn(-maxV, maxV)
                    body.vy = v.y.coerceIn(-maxV, maxV)
                    val (t0, a0) = angles.first()
                    val (t1, a1) = angles.last()
                    body.spin = if (t1 > t0) ((a1 - a0) / ((t1 - t0) / 1000f)).coerceIn(-2400f, 2400f) else 0f
                    haptics.release((hypot(body.vx, body.vy) / 6000f).coerceIn(0.15f, 0.8f))
                    launchFly()
                }
            }
    )
    Image(
        painterResource(dev.vis.ark.R.drawable.ic_launcher_fg), "Ark",
        Modifier
            .align(Alignment.Center)
            .requiredSize(180.dp)
            .graphicsLayer {
                translationX = body.x; translationY = body.y
                rotationZ = body.angle
                scaleX = body.scale; scaleY = body.scale
            },
    )
}

private fun rotate(p: Offset, theta: Float): Offset {
    val c = cos(theta); val s = sin(theta)
    return Offset(p.x * c - p.y * s, p.x * s + p.y * c)
}

/** Free flight: shake force, drag, wall bounces that trade sliding for spin; ends once it has come to rest. */
private suspend fun fly(b: LogoBody, haptics: ToyHaptics, radius: Float, w: Float, h: Float) {
    val maxX = (w / 2f - radius).coerceAtLeast(0f)
    val maxY = (h / 2f - radius).coerceAtLeast(0f)
    val restitution = 0.72f
    val grip = 0.35f // how much a wall turns sliding into rolling
    var last = withFrameNanos { it }
    var resting = 0f
    var lastTick = b.angle
    while (true) {
        val now = withFrameNanos { it }
        val dt = ((now - last) / 1e9f).coerceIn(0f, 1f / 30f)
        last = now
        b.vx = (b.vx + b.ax * dt).coerceIn(-12000f, 12000f)
        b.vy = (b.vy + b.ay * dt).coerceIn(-12000f, 12000f)
        b.x += b.vx * dt; b.y += b.vy * dt; b.angle += b.spin * dt
        val drag = exp(-1.1f * dt)
        b.vx *= drag; b.vy *= drag; b.spin *= exp(-1.4f * dt)

        // Walls. Rolling without slipping would need spin = ±(tangential speed / radius); each hit moves part
        // of the way there, so scraping along a wall sets it spinning the natural way.
        val toDeg = (180f / PI).toFloat()
        if (b.x > maxX && b.vx > 0f) { b.x = maxX; haptics.bounce(b.vx); b.vx *= -restitution; b.spin += (-b.vy / radius * toDeg - b.spin) * grip }
        if (b.x < -maxX && b.vx < 0f) { b.x = -maxX; haptics.bounce(b.vx); b.vx *= -restitution; b.spin += (b.vy / radius * toDeg - b.spin) * grip }
        if (b.y > maxY && b.vy > 0f) { b.y = maxY; haptics.bounce(b.vy); b.vy *= -restitution; b.spin += (b.vx / radius * toDeg - b.spin) * grip }
        if (b.y < -maxY && b.vy < 0f) { b.y = -maxY; haptics.bounce(b.vy); b.vy *= -restitution; b.spin += (-b.vx / radius * toDeg - b.spin) * grip }

        // Soft ticks while it whirls, faster spin → a little stronger.
        if (abs(b.angle - lastTick) >= 60f) {
            if (abs(b.spin) > 240f) haptics.spinTick((abs(b.spin) / 2400f).coerceIn(0.08f, 0.4f))
            lastTick = b.angle
        }

        val slow = hypot(b.vx, b.vy) < 40f && abs(b.spin) < 30f && b.ax == 0f && b.ay == 0f
        resting = if (slow) resting + dt else 0f
        if (resting > 0.25f) break
    }
}

/** Springs back to the middle, upright (the nearest full turn, so it doesn't unwind). */
private suspend fun settle(b: LogoBody, haptics: ToyHaptics) = coroutineScope {
    val spec = spring<Float>(dampingRatio = 0.62f, stiffness = Spring.StiffnessVeryLow)
    val home = floor((b.angle + 180f) / 360f) * 360f
    val far = hypot(b.x, b.y) > 4f || abs(b.angle - home) > 2f
    launch { animate(b.x, 0f, b.vx, spec) { v, vel -> b.x = v; b.vx = vel } }
    launch { animate(b.y, 0f, b.vy, spec) { v, vel -> b.y = v; b.vy = vel } }
    launch { animate(b.angle, home, b.spin, spring(0.55f, Spring.StiffnessVeryLow)) { v, vel -> b.angle = v; b.spin = vel } }
    if (far) launch { delay(650); haptics.release(0.12f) }
}

/** Haptics through the vibrator's primitives when the phone has them (strength per hit), plain clicks otherwise. */
private class ToyHaptics(private val view: View) {
    private val vibrator: Vibrator? = runCatching {
        val ctx = view.context
        if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }.getOrNull()
    private val primitives = Build.VERSION.SDK_INT >= 30 && vibrator?.hasVibrator() == true &&
        vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
    private val thud = primitives && Build.VERSION.SDK_INT >= 31 && vibrator!!.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)
    private var lastAt = 0L

    private fun play(primitive: Int, scale: Float, fallback: Int, minGapMs: Long = 0) {
        val now = System.currentTimeMillis()
        if (now - lastAt < minGapMs) return
        lastAt = now
        if (primitives && Build.VERSION.SDK_INT >= 30) {
            val effect = VibrationEffect.startComposition().addPrimitive(primitive, scale.coerceIn(0f, 1f)).compose()
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) vibrator!!.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
                else vibrator!!.vibrate(effect)
            }
        } else view.performHapticFeedback(fallback)
    }

    fun grab() = play(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f, HapticFeedbackConstants.LONG_PRESS)
    fun release(strength: Float) = play(VibrationEffect.Composition.PRIMITIVE_CLICK, strength, HapticFeedbackConstants.CLOCK_TICK)
    fun spinTick(strength: Float) = play(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, strength, HapticFeedbackConstants.CLOCK_TICK, minGapMs = 35)
    fun bounce(speed: Float) {
        val s = (abs(speed) / 5000f).coerceIn(0.12f, 1f)
        if (abs(speed) < 120f) return
        play(
            if (thud) VibrationEffect.Composition.PRIMITIVE_THUD else VibrationEffect.Composition.PRIMITIVE_CLICK, s,
            if (s > 0.5f) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY,
        )
    }
}
