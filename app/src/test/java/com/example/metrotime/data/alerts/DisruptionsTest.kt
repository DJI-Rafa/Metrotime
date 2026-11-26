package com.example.metrotime.data.alerts

import com.example.metrotime.data.cp.CpNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DisruptionsTest {

    // Resposta real do estadoLinha/todos com tudo normal
    private val allOk = mapOf(
        "amarela" to " Ok", "azul" to " Ok", "verde" to " Ok", "vermelha" to " Ok",
        "tipo_msg_am" to "0", "tipo_msg_az" to "0", "tipo_msg_vd" to "0", "tipo_msg_vm" to "0",
        "azul_curta" to "normal", "amarela_curta" to "normal", "verde_curta" to "normal", "vermelha_curta" to "normal"
    )

    private fun withGreen(text: String, short: String = "perturbacao") =
        allOk + mapOf("verde" to text, "verde_curta" to short, "tipo_msg_vd" to "2")

    @Test
    fun parsesAllLinesOk() {
        val statuses = MetroLineStatusParser.parse(allOk)
        assertEquals(listOf("Azul", "Amarela", "Verde", "Vermelha"), statuses.map { it.line })
        assertTrue(statuses.all { it.ok && it.message.isEmpty() })
    }

    @Test
    fun parsesDisruptionMessage() {
        val green = MetroLineStatusParser.parse(withGreen(" Circulação interrompida entre Cais do Sodré e Baixa-Chiado."))
            .first { it.line == "Verde" }
        assertFalse(green.ok)
        assertEquals("Circulação interrompida entre Cais do Sodré e Baixa-Chiado.", green.message)
        // "Ok" no texto mas o estado curto já não é "normal": também conta
        val shortOnly = MetroLineStatusParser.parse(withGreen(" Ok", "perturbacao")).first { it.line == "Verde" }
        assertFalse(shortOnly.ok)
        assertTrue(shortOnly.message.isNotBlank())
    }

    @Test
    fun alertsWhenLineStopsAndOnlyOnceWhileItLasts() {
        val down = MetroLineStatusParser.parse(withGreen("Circulação interrompida."))
        val (s1, a1) = MetroDisruptions.step(emptyMap(), down)
        assertEquals(listOf(LineAlert.Started("Verde", "Circulação interrompida.")), a1)
        // Mesma mensagem: nada de novo
        val (s2, a2) = MetroDisruptions.step(s1, down)
        assertTrue(a2.isEmpty())
        // Mensagem nova: atualiza (sem voltar a tocar)
        val (_, a3) = MetroDisruptions.step(s2, MetroLineStatusParser.parse(withGreen("Circulação com perturbações.")))
        assertEquals(listOf(LineAlert.Updated("Verde", "Circulação com perturbações.")), a3)
    }

    @Test
    fun endsOnlyAfterTwoOkReadings() {
        val (down, _) = MetroDisruptions.step(emptyMap(), MetroLineStatusParser.parse(withGreen("Interrompida.")))
        val ok = MetroLineStatusParser.parse(allOk)
        val (once, a1) = MetroDisruptions.step(down, ok)
        assertTrue("uma leitura Ok ainda não chega", a1.isEmpty())
        val (twice, a2) = MetroDisruptions.step(once, ok)
        assertEquals(listOf(LineAlert.Ended("Verde")), a2)
        assertFalse(twice.getValue("Verde").disrupted)
        // Já normal: não volta a avisar
        assertTrue(MetroDisruptions.step(twice, ok).second.isEmpty())
    }

    @Test
    fun flappingBackDownKeepsItAsOneDisruption() {
        val downList = MetroLineStatusParser.parse(withGreen("Interrompida."))
        val (down, _) = MetroDisruptions.step(emptyMap(), downList)
        val (blip, _) = MetroDisruptions.step(down, MetroLineStatusParser.parse(allOk))
        // Volta a cair logo a seguir com a mesma mensagem: não é uma perturbação nova
        assertTrue(MetroDisruptions.step(blip, downList).second.isEmpty())
    }

    // ── Avisos CP ─────────────────────────────────────────────────────────

    private fun notice(title: String) = CpNotice(title = title, start = LocalDate.of(2026, 10, 25))

    @Test
    fun firstRunOnlyRemembersExistingNotices() {
        val existing = listOf(notice("Linha do Norte - obras"), notice("Linha de Sintra - supressões"))
        val (fresh, seen) = CpNoticeAlerts.pickNew(existing, seen = null)
        assertTrue(fresh.isEmpty())
        assertEquals(2, seen.size)
    }

    @Test
    fun newNoticesAreShownOnceAndCapped() {
        val old = notice("Linha do Norte - obras")
        val (_, seen) = CpNoticeAlerts.pickNew(listOf(old), seen = null)
        val news = (1..5).map { notice("Aviso $it") }
        val (fresh, seen2) = CpNoticeAlerts.pickNew(listOf(old) + news, seen)
        assertEquals(3, fresh.size)
        assertFalse(fresh.contains(old))
        // Na verificação seguinte já não se repetem
        assertTrue(CpNoticeAlerts.pickNew(listOf(old) + news, seen2).first.isEmpty())
    }
}
