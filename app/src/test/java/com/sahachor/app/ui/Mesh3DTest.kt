package com.sahachor.app.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class Mesh3DTest {
    @Test fun rotationKeepsDistanceFromCenter() {
        val r = Mesh3D.rotate(V3(1f, 1f, 1f), 0.7f, 1.3f)
        assertEquals(sqrt(3f), sqrt(r.x * r.x + r.y * r.y + r.z * r.z), 1e-4f)
    }

    @Test fun projectionKeepsCenterAndNearPointsLookBigger() {
        assertEquals(0f, Mesh3D.project(V3(0f, 0f, 0f)).first, 1e-6f)
        val far = Mesh3D.project(V3(1f, 0f, -1f)).first
        val near = Mesh3D.project(V3(1f, 0f, 1f)).first
        assertTrue(near > far)
    }

    @Test fun cubeHasSixShadedQuads() {
        val faces = Mesh3D.faces(0.4f, 0.9f)
        assertEquals(6, faces.size)
        faces.forEach { (corners, shade) ->
            assertEquals(4, corners.size)
            assertTrue(shade in 0.449f..1.001f)
        }
    }

    @Test fun normalsAreUnitLength() {
        val n = Mesh3D.normal(V3(-1f, -1f, -1f), V3(1f, -1f, -1f), V3(1f, 1f, -1f))
        assertEquals(1f, sqrt(n.x * n.x + n.y * n.y + n.z * n.z), 1e-5f)
        assertEquals(1f, abs(n.z), 1e-5f)
    }
}
