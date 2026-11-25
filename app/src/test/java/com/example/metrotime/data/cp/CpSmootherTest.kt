package com.example.metrotime.data.cp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpSmootherTest {

    private val dt = 0.08

    @Test
    fun followsNormalMovementWithoutLag() {
        val s = CpSmoother()
        assertEquals(1_000.0, s.step(1, 1_000.0, dt), 1e-9)
        // 40 m/s: 3,2 m por imagem
        var target = 1_000.0
        repeat(50) {
            target += 3.2
            assertEquals(target, s.step(1, target, dt), 1e-6)
        }
    }

    @Test
    fun newDataIsBlendedInAboutASecondAndAHalf() {
        val s = CpSmoother()
        s.step(1, 1_000.0, dt)
        // Dados novos: está 500 m mais à frente
        val first = s.step(1, 1_500.0, dt, newData = true)
        assertTrue("não salta: $first", first < 1_050.0)
        var shown = first
        repeat(19) { shown = s.step(1, 1_500.0, dt) } // 1,6 s
        assertTrue("já quase lá: $shown", shown > 1_450.0)
        assertTrue(shown <= 1_500.0 + 1e-6)
    }

    @Test
    fun neverGoesBackwardsForASmallCorrection() {
        val s = CpSmoother()
        s.step(1, 2_000.0, dt)
        // O GPS novo diz que está 300 m atrás: fica parado até a conta o apanhar
        var target = 1_700.0
        var last = s.step(1, target, dt, newData = true)
        assertEquals(2_000.0, last, 1e-6)
        repeat(200) {
            target += 3.0
            val now = s.step(1, target, dt)
            assertTrue("andou para trás: $now < $last", now >= last - 1e-9)
            last = now
        }
        // Depois de a conta passar o sítio onde estava, volta a andar com ela
        assertTrue(last > 2_000.0)
        assertEquals(target, last, 1.0)
    }

    @Test
    fun aHugeChangeGoesAtOnce() {
        val s = CpSmoother()
        s.step(1, 1_000.0, dt)
        assertEquals(20_000.0, s.step(1, 20_000.0, dt, newData = true), 1e-9)
    }

    @Test
    fun aLargeErrorBackwardsIsUndoneSlowly() {
        val s = CpSmoother()
        s.step(1, 10_000.0, dt)
        // Estava 3 km à frente do que devia: volta, mas não de uma vez
        val first = s.step(1, 7_000.0, dt, newData = true)
        assertTrue(first > 9_000.0)
        var shown = first
        repeat(40) { shown = s.step(1, 7_000.0, dt) }
        assertEquals(7_000.0, shown, 50.0)
    }

    @Test
    fun forgetsTrainsThatLeft() {
        val s = CpSmoother()
        s.step(1, 1_000.0, dt)
        s.retain(emptySet())
        // Volta a aparecer noutro sítio: começa lá
        assertEquals(5_000.0, s.step(1, 5_000.0, dt), 1e-9)
    }
}
