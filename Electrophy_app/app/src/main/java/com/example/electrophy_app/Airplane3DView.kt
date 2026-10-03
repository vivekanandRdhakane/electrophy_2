package com.example.electrophy_app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.*

/** 3D Vector with linear algebra helpers. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(v: Vec3) = Vec3(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: Vec3) = Vec3(x - v.x, y - v.y, z - v.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)

    fun dot(v: Vec3) = x * v.x + y * v.y + z * v.z

    fun cross(v: Vec3) = Vec3(
        y * v.z - z * v.y,
        z * v.x - x * v.z,
        x * v.y - y * v.x
    )

    fun length() = sqrt(x * x + y * y + z * z)

    fun normalize(): Vec3 {
        val l = length()
        return if (l > 1e-6f) Vec3(x / l, y / l, z / l) else Vec3(0f, 0f, 1f)
    }

    // Aircraft Euler Rotations (Y = forward, X = right, Z = up)
    fun rotateRoll(rollRad: Float): Vec3 {
        val c = cos(rollRad); val s = sin(rollRad)
        return Vec3(x * c - z * s, y, x * s + z * c)
    }

    fun rotatePitch(pitchRad: Float): Vec3 {
        val c = cos(pitchRad); val s = sin(pitchRad)
        return Vec3(x, y * c - z * s, y * s + z * c)
    }

    fun rotateYaw(yawRad: Float): Vec3 {
        val c = cos(yawRad); val s = sin(yawRad)
        return Vec3(x * c + y * s, -x * s + y * c, z)
    }

    fun rotateEuler(yawRad: Float, pitchRad: Float, rollRad: Float): Vec3 {
        // Apply Roll -> Pitch -> Yaw
        return this.rotateRoll(rollRad).rotatePitch(pitchRad).rotateYaw(yawRad)
    }
}

/** A 3D polygon face for mesh rendering. */
private data class PolyFace(
    val v: List<Vec3>,
    val baseColor: Color,
    val isDoubleSided: Boolean = true,
    val isOutlineOnly: Boolean = false,
    val outlineColor: Color = Color(0x33FFFFFF)
)

enum class CameraViewMode(val label: String) {
    CHASE("Chase View"),
    TOP_DOWN("Top View"),
    PILOT("Cockpit HUD")
}

@Composable
fun Airplane3DView(
    pitchDeg: Float,
    rollDeg: Float,
    yawDeg: Float,
    gForce: Float,
    turnRateDps: Float,
    onZeroHeading: () -> Unit,
    modifier: Modifier = Modifier
) {
    var cameraMode by remember { mutableStateOf(CameraViewMode.CHASE) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0B1017))
    ) {
        // 3D Canvas Rendering
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val cx = width / 2f
            val cy = height / 2f

            // 1. Draw Flight HUD / Artificial Horizon in background
            drawArtificialHorizon(pitchDeg, rollDeg, width, height)

            // 2. Draw 3D Airplane Mesh (if in Chase or Top-Down mode)
            if (cameraMode != CameraViewMode.PILOT) {
                drawAirplaneMesh(
                    yawDeg = yawDeg,
                    pitchDeg = pitchDeg,
                    rollDeg = rollDeg,
                    cameraMode = cameraMode,
                    cx = cx,
                    cy = cy
                )
            }

            // 3. Draw Cockpit Reticle / Crosshair
            drawFlightReticle(cx, cy)
        }

        // Top Controls: Camera View Selector & Zero Heading Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Camera Mode Selector
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CameraViewMode.entries.forEach { mode ->
                    Surface(
                        onClick = { cameraMode = mode },
                        shape = RoundedCornerShape(12.dp),
                        color = if (cameraMode == mode) MaterialTheme.colorScheme.primary else Color(0x661E293B),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (cameraMode == mode) Color.Black else Color.LightGray,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Zero Heading Button
            Button(
                onClick = onZeroHeading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text("🎯 Zero Heading", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }

        // Bottom Flight Telemetry Overlay Cards
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TelemetryBadge(
                label = "PITCH",
                value = String.format(Locale.US, "%+.1f°", pitchDeg),
                sub = if (pitchDeg >= 0) "CLIMB" else "DIVE",
                accentColor = if (pitchDeg >= 0) Color(0xFF38BDF8) else Color(0xFFF43F5E)
            )
            TelemetryBadge(
                label = "ROLL",
                value = String.format(Locale.US, "%+.1f°", rollDeg),
                sub = if (rollDeg > 2f) "RIGHT" else if (rollDeg < -2f) "LEFT" else "LEVEL",
                accentColor = if (abs(rollDeg) > 5f) Color(0xFFFBBF24) else Color(0xFF4ADE80)
            )
            TelemetryBadge(
                label = "YAW / HDG",
                value = String.format(Locale.US, "%03.0f°", (yawDeg % 360f + 360f) % 360f),
                sub = getCompassHeading((yawDeg % 360f + 360f) % 360f),
                accentColor = Color(0xFFA855F7)
            )
            TelemetryBadge(
                label = "G-FORCE",
                value = String.format(Locale.US, "%.2f G", gForce),
                sub = String.format(Locale.US, "%.0f°/s", turnRateDps),
                accentColor = if (gForce > 1.8f) Color(0xFFEF4444) else Color(0xFF06B6D4)
            )
        }
    }
}

@Composable
private fun TelemetryBadge(label: String, value: String, sub: String, accentColor: Color) {
    Surface(
        color = Color(0xD90F172A),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
        modifier = Modifier.padding(horizontal = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 9.sp)
            Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = accentColor, fontSize = 13.sp)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f), fontSize = 8.sp)
        }
    }
}

private fun getCompassHeading(heading: Float): String {
    val dirs = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    val idx = (((heading + 11.25f) % 360f) / 22.5f).toInt()
    return dirs[idx.coerceIn(0, 15)]
}

/** Draws artificial horizon ladder and ground/sky indicator. */
private fun DrawScope.drawArtificialHorizon(pitchDeg: Float, rollDeg: Float, w: Float, h: Float) {
    val cx = w / 2f
    val cy = h / 2f
    val rollRad = Math.toRadians(-rollDeg.toDouble()).toFloat()

    // Pitch shift (pixels per degree)
    val pitchPixelsPerDegree = 3.5f
    val pitchOffset = pitchDeg * pitchPixelsPerDegree

    // Draw Ground / Sky separator line (Horizon)
    val horizonLength = w * 0.75f
    val cosR = cos(rollRad)
    val sinR = sin(rollRad)

    // Center of horizon shifted by pitch along roll axis
    val hx = cx - pitchOffset * sinR
    val hy = cy + pitchOffset * cosR

    val p1 = Offset(hx - horizonLength * cosR, hy - horizonLength * sinR)
    val p2 = Offset(hx + horizonLength * cosR, hy + horizonLength * sinR)

    drawLine(
        color = Color(0xFF0284C7).copy(alpha = 0.45f),
        start = p1,
        end = p2,
        strokeWidth = 2.dp.toPx()
    )

    // Pitch Ladder rungs (+10, +20, -10, -20)
    for (deg in listOf(-20, -10, 10, 20)) {
        val rungOffset = (pitchDeg - deg) * pitchPixelsPerDegree
        val rx = cx - rungOffset * sinR
        val ry = cy + rungOffset * cosR
        val rungW = if (abs(deg) == 10) 30.dp.toPx() else 45.dp.toPx()

        val rp1 = Offset(rx - rungW * cosR, ry - rungW * sinR)
        val rp2 = Offset(rx + rungW * cosR, ry + rungW * sinR)

        val rungColor = if (deg > 0) Color(0xFF38BDF8).copy(alpha = 0.35f) else Color(0xFFF97316).copy(alpha = 0.35f)
        drawLine(color = rungColor, start = rp1, end = rp2, strokeWidth = 1.5.dp.toPx())
    }
}

/** Draws center reticle / crosshair. */
private fun DrawScope.drawFlightReticle(cx: Float, cy: Float) {
    val yellow = Color(0xFFFACC15).copy(alpha = 0.85f)
    val size = 16.dp.toPx()

    // Center dot
    drawCircle(color = yellow, radius = 2.dp.toPx(), center = Offset(cx, cy))

    // Left wing reticle
    drawLine(color = yellow, start = Offset(cx - size * 2.2f, cy), end = Offset(cx - size * 0.6f, cy), strokeWidth = 2.dp.toPx())
    drawLine(color = yellow, start = Offset(cx - size * 2.2f, cy), end = Offset(cx - size * 2.2f, cy + size * 0.5f), strokeWidth = 2.dp.toPx())

    // Right wing reticle
    drawLine(color = yellow, start = Offset(cx + size * 0.6f, cy), end = Offset(cx + size * 2.2f, cy), strokeWidth = 2.dp.toPx())
    drawLine(color = yellow, start = Offset(cx + size * 2.2f, cy), end = Offset(cx + size * 2.2f, cy + size * 0.5f), strokeWidth = 2.dp.toPx())
}

/** Builds and projects the 3D aircraft model. */
private fun DrawScope.drawAirplaneMesh(
    yawDeg: Float,
    pitchDeg: Float,
    rollDeg: Float,
    cameraMode: CameraViewMode,
    cx: Float,
    cy: Float
) {
    // 1. Define Model Vertices (Jet Aircraft)
    // Axes: +Y is forward (nose), +X is right (wing), +Z is up (canopy/fin)
    val noseTip       = Vec3(0f, 130f, -2f)
    val noseTop       = Vec3(0f, 65f, 12f)
    val noseBottom    = Vec3(0f, 65f, -12f)
    val noseLeft      = Vec3(-18f, 65f, 0f)
    val noseRight     = Vec3(18f, 65f, 0f)

    val canopyTop     = Vec3(0f, 25f, 22f)
    val canopyBack    = Vec3(0f, -15f, 16f)

    val fuseMidTop    = Vec3(0f, -30f, 15f)
    val fuseMidBottom = Vec3(0f, -30f, -12f)
    val fuseMidLeft   = Vec3(-20f, -30f, 0f)
    val fuseMidRight  = Vec3(20f, -30f, 0f)

    val fuseTailTop   = Vec3(0f, -110f, 10f)
    val fuseTailBottom= Vec3(0f, -110f, -6f)
    val fuseTailLeft  = Vec3(-10f, -110f, 2f)
    val fuseTailRight = Vec3(10f, -110f, 2f)

    // Wings
    val wingLRootFront = Vec3(-18f, 40f, 0f)
    val wingLRootBack  = Vec3(-20f, -45f, 0f)
    val wingLTipFront  = Vec3(-145f, -20f, 4f)
    val wingLTipBack   = Vec3(-145f, -35f, 4f)

    val wingRRootFront = Vec3(18f, 40f, 0f)
    val wingRRootBack  = Vec3(20f, -45f, 0f)
    val wingRTipFront  = Vec3(145f, -20f, 4f)
    val wingRTipBack   = Vec3(145f, -35f, 4f)

    // Vertical Fin (Tail)
    val finBaseFront   = Vec3(0f, -60f, 13f)
    val finBaseBack    = Vec3(0f, -115f, 9f)
    val finTopFront    = Vec3(0f, -110f, 52f)
    val finTopBack     = Vec3(0f, -125f, 48f)

    // Horizontal Elevators
    val elevLRootFront = Vec3(-8f, -95f, 4f)
    val elevLRootBack  = Vec3(-8f, -115f, 4f)
    val elevLTip       = Vec3(-55f, -120f, 4f)

    val elevRRootFront = Vec3(8f, -95f, 4f)
    val elevRRootBack  = Vec3(8f, -115f, 4f)
    val elevRTip       = Vec3(55f, -120f, 4f)

    // Color definitions
    val bodyColor    = Color(0xFFCBD5E1) // Aviation silver/white
    val bodyDark     = Color(0xFF94A3B8)
    val wingColor    = Color(0xFFE2E8F0)
    val wingUnderside= Color(0xFF64748B)
    val canopyColor  = Color(0xFF00E5FF).copy(alpha = 0.85f) // Glass cyan
    val finColor     = Color(0xFF2563EB) // Royal blue fin
    val elevColor    = Color(0xFF94A3B8)
    val redBeacon    = Color(0xFFEF4444)
    val greenBeacon  = Color(0xFF22C55E)

    // 2. Assemble Polygonal Faces
    val faces = listOf(
        // Nose Cone
        PolyFace(listOf(noseTip, noseTop, noseLeft), bodyColor),
        PolyFace(listOf(noseTip, noseRight, noseTop), bodyColor),
        PolyFace(listOf(noseTip, noseLeft, noseBottom), bodyDark),
        PolyFace(listOf(noseTip, noseBottom, noseRight), bodyDark),

        // Canopy (Cockpit)
        PolyFace(listOf(noseTop, canopyTop, noseLeft), canopyColor),
        PolyFace(listOf(noseTop, noseRight, canopyTop), canopyColor),
        PolyFace(listOf(canopyTop, canopyBack, fuseMidLeft), canopyColor),
        PolyFace(listOf(canopyTop, fuseMidRight, canopyBack), canopyColor),

        // Fuselage Body
        PolyFace(listOf(noseLeft, fuseMidTop, fuseMidLeft), bodyColor),
        PolyFace(listOf(noseRight, fuseMidRight, fuseMidTop), bodyColor),
        PolyFace(listOf(noseLeft, fuseMidLeft, fuseMidBottom, noseBottom), bodyDark),
        PolyFace(listOf(noseRight, noseBottom, fuseMidBottom, fuseMidRight), bodyDark),

        PolyFace(listOf(fuseMidTop, fuseTailTop, fuseTailLeft, fuseMidLeft), bodyColor),
        PolyFace(listOf(fuseMidTop, fuseMidRight, fuseTailRight, fuseTailTop), bodyColor),
        PolyFace(listOf(fuseMidLeft, fuseTailLeft, fuseTailBottom, fuseMidBottom), bodyDark),
        PolyFace(listOf(fuseMidRight, fuseMidBottom, fuseTailBottom, fuseTailRight), bodyDark),

        // Left Main Wing (Top & Bottom)
        PolyFace(listOf(wingLRootFront, wingLTipFront, wingLTipBack, wingLRootBack), wingColor),
        PolyFace(listOf(wingLRootBack, wingLTipBack, wingLTipFront, wingLRootFront), wingUnderside),

        // Right Main Wing (Top & Bottom)
        PolyFace(listOf(wingRRootFront, wingRRootBack, wingRTipBack, wingRTipFront), wingColor),
        PolyFace(listOf(wingRRootBack, wingRRootFront, wingRTipFront, wingRTipBack), wingUnderside),

        // Wingtip Navigation Beacons
        PolyFace(listOf(wingLTipFront, wingLTipBack, Vec3(-148f, -27f, 4f)), redBeacon),
        PolyFace(listOf(wingRTipFront, Vec3(148f, -27f, 4f), wingRTipBack), greenBeacon),

        // Vertical Tail Fin
        PolyFace(listOf(finBaseFront, finTopFront, finTopBack, finBaseBack), finColor),
        PolyFace(listOf(finBaseBack, finTopBack, finTopFront, finBaseFront), finColor),

        // Horizontal Stabilizers (Elevators)
        PolyFace(listOf(elevLRootFront, elevLTip, elevLRootBack), elevColor),
        PolyFace(listOf(elevRRootFront, elevRRootBack, elevRTip), elevColor)
    )

    // 3. Transformation Angles in Radians
    // In Chase View:
    // When the airplane rolls right (rollDeg > 0), right wing dips down.
    // When the airplane pitches up (pitchDeg > 0), nose climbs up.
    // When the airplane yaws right (yawDeg > 0), nose turns right.
    val rollRad  = Math.toRadians(rollDeg.toDouble()).toFloat()
    val pitchRad = Math.toRadians(pitchDeg.toDouble()).toFloat()
    val yawRad   = Math.toRadians(yawDeg.toDouble()).toFloat()

    // Camera setup
    val cameraDist = 420f
    val fov = 450f // Focal length

    val lightDir = Vec3(0.4f, -0.5f, 0.77f).normalize()

    // 4. Transform and Depth-Sort Faces (Painter's Algorithm)
    data class TransformedFace(
        val pts2d: List<Offset>,
        val avgDepth: Float,
        val shadedColor: Color
    )

    val transformedList = mutableListOf<TransformedFace>()

    for (face in faces) {
        val transformedVerts = face.v.map { v ->
            var p = v
            // Apply Airplane Attitude Rotations
            p = p.rotateRoll(rollRad)
            p = p.rotatePitch(pitchRad)
            p = p.rotateYaw(yawRad)

            // Apply Camera View Orientation
            when (cameraMode) {
                CameraViewMode.CHASE -> {
                    // Look at aircraft from behind (+Y is forward, camera is behind at -Y, slightly elevated in +Z)
                    // Rotate world so camera looks down -Y axis with an elevation tilt of ~18 degrees
                    val camElevation = Math.toRadians(18.0).toFloat()
                    val c = cos(camElevation); val s = sin(camElevation)
                    Vec3(p.x, p.y * c + p.z * s, -p.y * s + p.z * c)
                }
                CameraViewMode.TOP_DOWN -> {
                    // Look straight down at the top of the airplane
                    Vec3(p.x, -p.z, p.y)
                }
                CameraViewMode.PILOT -> p
            }
        }

        // Calculate surface normal for dynamic lighting
        val v0 = transformedVerts[0]
        val v1 = transformedVerts[1]
        val v2 = transformedVerts[2]
        val normal = (v1 - v0).cross(v2 - v0).normalize()

        // Directional Lambertian Shading
        val lightFactor = (0.4f + 0.6f * max(0f, normal.dot(lightDir))).coerceIn(0.2f, 1f)
        val shadedColor = Color(
            red = (face.baseColor.red * lightFactor).coerceIn(0f, 1f),
            green = (face.baseColor.green * lightFactor).coerceIn(0f, 1f),
            blue = (face.baseColor.blue * lightFactor).coerceIn(0f, 1f),
            alpha = face.baseColor.alpha
        )

        // Project 3D to 2D screen coordinates
        var avgZ = 0f
        val projected2D = transformedVerts.map { p ->
            val depth = p.y + cameraDist
            avgZ += depth
            val scale = fov / max(10f, depth)
            Offset(
                x = cx + p.x * scale,
                y = cy - p.z * scale // Screen Y is inverted
            )
        }
        avgZ /= transformedVerts.size

        transformedList.add(TransformedFace(projected2D, avgZ, shadedColor))
    }

    // Sort by depth descending (draw furthest polygons first)
    transformedList.sortByDescending { it.avgDepth }

    // 5. Draw the shaded polygons
    for (tf in transformedList) {
        if (tf.pts2d.size < 3) continue
        val path = Path().apply {
            moveTo(tf.pts2d[0].x, tf.pts2d[0].y)
            for (i in 1 until tf.pts2d.size) {
                lineTo(tf.pts2d[i].x, tf.pts2d[i].y)
            }
            close()
        }

        // Draw solid shaded facet
        drawPath(path = path, color = tf.shadedColor)

        // Draw subtle outline for clean engineering/avionics aesthetic
        drawPath(
            path = path,
            color = Color(0x33FFFFFF),
            style = Stroke(width = 0.8.dp.toPx())
        )
    }
}
