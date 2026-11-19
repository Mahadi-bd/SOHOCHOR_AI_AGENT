package com.sahachor.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class V3(val x: Float, val y: Float, val z: Float)

/** A tiny 3D engine: rotation, perspective and flat shading. Pure maths, so it is unit-tested and works offline. */
object Mesh3D {
    val cubeVertices = listOf(
        V3(-1f, -1f, -1f), V3(1f, -1f, -1f), V3(1f, 1f, -1f), V3(-1f, 1f, -1f),
        V3(-1f, -1f, 1f), V3(1f, -1f, 1f), V3(1f, 1f, 1f), V3(-1f, 1f, 1f),
    )
    val cubeFaces = listOf(
        intArrayOf(0, 1, 2, 3), intArrayOf(4, 5, 6, 7), intArrayOf(0, 1, 5, 4),
        intArrayOf(3, 2, 6, 7), intArrayOf(0, 3, 7, 4), intArrayOf(1, 2, 6, 5),
    )

    /** Rotates around the Y axis by ay, then around the X axis by ax (radians). */
    fun rotate(v: V3, ax: Float, ay: Float): V3 {
        val cy = cos(ay); val sy = sin(ay)
        val x1 = v.x * cy + v.z * sy
        val z1 = -v.x * sy + v.z * cy
        val cx = cos(ax); val sx = sin(ax)
        return V3(x1, v.y * cx - z1 * sx, v.y * sx + z1 * cx)
    }

    /** Perspective: points with larger z are closer to the viewer and look larger. */
    fun project(v: V3, camera: Float = 4f): Pair<Float, Float> {
        val f = camera / (camera - v.z)
        return Pair(v.x * f, v.y * f)
    }

    fun normal(a: V3, b: V3, c: V3): V3 {
        val ux = b.x - a.x; val uy = b.y - a.y; val uz = b.z - a.z
        val vx = c.x - a.x; val vy = c.y - a.y; val vz = c.z - a.z
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        return if (len == 0f) V3(0f, 0f, 1f) else V3(nx / len, ny / len, nz / len)
    }

    private val light = V3(0.3f, 0.5f, 0.8f).let { l -> val n = sqrt(l.x * l.x + l.y * l.y + l.z * l.z); V3(l.x / n, l.y / n, l.z / n) }

    /** Brightness 0.45..1.0 of a face, from the angle between its normal and the light (two-sided). */
    fun brightness(n: V3): Float = 0.45f + 0.55f * abs(n.x * light.x + n.y * light.y + n.z * light.z)

    /** Faces ordered far to near, each with its projected corners and brightness. */
    fun faces(ax: Float, ay: Float): List<Pair<List<Pair<Float, Float>>, Float>> {
        val rotated = cubeVertices.map { rotate(it, ax, ay) }
        return cubeFaces
            .sortedBy { f -> f.map { rotated[it].z }.average() }
            .map { f ->
                val pts = f.map { rotated[it] }
                Pair(pts.map { project(it) }, brightness(normal(pts[0], pts[1], pts[2])))
            }
    }
}

/** A turning 3D cube in the given colour, used as the loader while the agent works. */
@Composable
fun Spinner3D(modifier: Modifier, color: Color) {
    val t = rememberInfiniteTransition(label = "cube")
    val ay by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "ay")
    val ax by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(8200, easing = LinearEasing)), label = "ax")
    Canvas(modifier) {
        val scale = size.minDimension / 4.6f
        val cx = size.width / 2f
        val cy = size.height / 2f
        for ((pts, shade) in Mesh3D.faces(ax + 0.5f, ay)) {
            val path = Path()
            pts.forEachIndexed { i, p ->
                val o = Offset(cx + p.first * scale, cy - p.second * scale)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            path.close()
            drawPath(path, Color(color.red * shade, color.green * shade, color.blue * shade, 1f))
            drawPath(path, Color.White.copy(alpha = 0.55f), style = Stroke(width = 1.5f))
        }
    }
}
