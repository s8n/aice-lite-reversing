package zone.ave.aicelite.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five status notifications captured from a live `LH-Aice3 Lite`
 * (PROTOCOL.md §4). If the codec can't reproduce their CRCs, nothing else in
 * this app is worth debugging.
 */
private data class CapturedFrame(val hex: String, val ambient: Int, val target: Int, val wind: Int)

private const val COMMON = "0200000000010201062201"  // payload[0..10], constant across the capture

private val CAPTURED_FRAMES = listOf(
    //            crc      payload[0..10]  ambient target wind  opt+unit
    CapturedFrame("00CC2976" + COMMON + "1C" + "16" + "29" + "0101", ambient = 28, target = 22, wind = 41),
    CapturedFrame("00CCBCED" + COMMON + "1C" + "1A" + "50" + "0101", ambient = 28, target = 26, wind = 80),
    CapturedFrame("00CCC030" + COMMON + "1C" + "1E" + "3C" + "0101", ambient = 28, target = 30, wind = 60),
    CapturedFrame("00CC73CA" + COMMON + "1C" + "15" + "5A" + "0101", ambient = 28, target = 21, wind = 90),
    CapturedFrame("00CC9A72" + COMMON + "1C" + "10" + "64" + "0101", ambient = 28, target = 16, wind = 100),
)

class AiceCodecTest {

    private fun hex(s: String) = parseHex(s)!!

    @Test
    fun `crc16arc matches the standard check vector`() {
        assertEquals(0xBB3D, AiceCodec.crc16arc("123456789".toByteArray()))
    }

    @Test
    fun `captured frames are 20 bytes`() {
        CAPTURED_FRAMES.forEach { assertEquals(20, hex(it.hex).size) }
    }

    @Test
    fun `crc of every captured frame recomputes exactly`() {
        CAPTURED_FRAMES.forEach { captured ->
            val frame = hex(captured.hex)
            assertTrue("CRC mismatch for ${captured.hex}", AiceCodec.crcValid(frame))
        }
    }

    @Test
    fun `re-encoding a captured payload reproduces the original frame`() {
        CAPTURED_FRAMES.forEach { captured ->
            val frame = hex(captured.hex)
            val payload = AiceCodec.decode(frame)!!
            assertArrayEquals(frame, AiceCodec.encode(payload))
        }
    }

    @Test
    fun `a plain crc over the payload alone does not match the device`() {
        // The keyed suffix is load-bearing; this guards against someone "cleaning up" checksum().
        val frame = hex(CAPTURED_FRAMES.first().hex)
        val payload = AiceCodec.decode(frame)!!
        val expected = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
        assertTrue(AiceCodec.crc16arc(payload) != expected)
        assertEquals(expected, AiceCodec.crc16arc(payload + AiceCodec.LH_KEY1))
    }

    @Test
    fun `fields decode at the documented offsets`() {
        CAPTURED_FRAMES.forEach { captured ->
            val state = AiceState.fromFrame(hex(captured.hex))!!
            assertEquals(captured.ambient, state.ambientTempC)
            assertEquals(captured.target, state.targetTempC)
            assertEquals(captured.wind, state.windLevel)
            assertEquals(Power.ON, state.power)
            assertEquals(Mode.COOLING, state.mode)
            assertEquals(TempUnit.CELSIUS, state.unit)
            assertEquals(0x22, state.flags)
            assertFalse(state.lightOn)
            assertFalse(state.voiceOn)
        }
    }

    @Test
    fun `frames with a bad header or sync byte are rejected`() {
        assertNull(AiceCodec.decode(hex("01CC29760200000000010201062201")))
        assertNull(AiceCodec.decode(hex("00CD29760200000000010201062201")))
        assertNull(AiceCodec.decode(byteArrayOf(0x00, 0xCC.toByte())))
        assertNull(AiceState.fromFrame(hex("00CC2976020000")))
    }

    @Test
    fun `parseHex tolerates the formats a human types`() {
        val expected = byteArrayOf(0x00, 0xCC.toByte(), 0x29, 0x76)
        assertArrayEquals(expected, parseHex("00 CC 29 76"))
        assertArrayEquals(expected, parseHex("00cc2976"))
        assertArrayEquals(expected, parseHex("0x00,0xCC,0x29,0x76"))
        assertArrayEquals(expected, parseHex("00:CC:29:76"))
        assertNull(parseHex("00 C"))
        assertNull(parseHex("zz"))
        assertNull(parseHex(""))
    }
}

class CommandsTest {

    /** Capture frame #1's payload: cooling, running, 22 °C target, wind 41. */
    private val running = AiceState(parseHex(COMMON + "1C" + "16" + "29" + "0101")!!)

    @Test
    fun `power on sends 4 from idle and 2 while already running`() {
        val idle = running.withByte(Idx.POWER, Power.IDLE.code)
        assertEquals(Power.TURN_ON.code, Commands.powerOn(idle).powerCode)
        assertEquals(Power.ON.code, Commands.powerOn(running).powerCode)
    }

    @Test
    fun `power off writes 3`() {
        assertEquals(Power.OFF.code, Commands.powerOff(running).powerCode)
    }

    @Test
    fun `mode switch is refused only while off`() {
        val off = running.withByte(Idx.POWER, Power.OFF.code)
        assertNull(Commands.setMode(off, Mode.HEATING))
        // Allowed while running and while paused.
        assertEquals(Mode.HEATING.sendCode, Commands.setMode(running, Mode.HEATING)!!.modeCode)
        val paused = running.withByte(Idx.POWER, Power.IDLE.code)
        assertEquals(Mode.HEATING.sendCode, Commands.setMode(paused, Mode.HEATING)!!.modeCode)
    }

    @Test
    fun `switching mode snaps the target temp into the new range`() {
        // running is cooling @ 22°C.
        val toHeat = Commands.setMode(running, Mode.HEATING)!!
        assertEquals(Mode.HEATING.sendCode, toHeat.modeCode)
        assertEquals(40, toHeat.targetTempC) // 22 clamps up into heating 40..50

        val hot = running.withByte(Idx.MODE, Mode.HEATING.code).withByte(Idx.TARGET_TEMP, 45)
        assertEquals(30, Commands.setMode(hot, Mode.COOLING)!!.targetTempC) // 45 clamps down into 16..30

        // Fan has no range: target left untouched.
        assertEquals(22, Commands.setMode(running, Mode.FAN)!!.targetTempC)
    }

    @Test
    fun `heating has no wind, other modes do`() {
        assertFalse(running.withByte(Idx.MODE, Mode.HEATING.code).supportsWind)
        assertTrue(running.withByte(Idx.MODE, Mode.COOLING.code).supportsWind)
        assertTrue(running.withByte(Idx.MODE, Mode.FAN.code).supportsWind)
    }

    @Test
    fun `temperature only moves while running and outside fan mode`() {
        assertEquals(23, Commands.adjustTemp(running, +1)!!.targetTempC)
        assertEquals(21, Commands.adjustTemp(running, -1)!!.targetTempC)

        val idle = running.withByte(Idx.POWER, Power.IDLE.code)
        assertNull(Commands.adjustTemp(idle, +1))

        val fan = running.withByte(Idx.MODE, Mode.FAN.code)
        assertNull("fan mode has no target temperature", Commands.adjustTemp(fan, +1))
    }

    @Test
    fun `temperature clamps to the mode's range`() {
        val atMax = running.withByte(Idx.TARGET_TEMP, 30)
        assertNull("cooling stops at 30C", Commands.adjustTemp(atMax, +1))

        val atMin = running.withByte(Idx.TARGET_TEMP, 16)
        assertNull("cooling stops at 16C", Commands.adjustTemp(atMin, -1))

        // Heating range 40..50 (operator-confirmed).
        val heatMax = running.withByte(Idx.MODE, Mode.HEATING.code).withByte(Idx.TARGET_TEMP, 50)
        assertNull(Commands.adjustTemp(heatMax, +1))
        assertEquals(49, Commands.adjustTemp(heatMax, -1)!!.targetTempC)
        val heatMin = running.withByte(Idx.MODE, Mode.HEATING.code).withByte(Idx.TARGET_TEMP, 40)
        assertNull(Commands.adjustTemp(heatMin, -1))
    }

    @Test
    fun `wind clamps to 0 through 100`() {
        assertEquals(100, Commands.setWind(running, 250).windLevel)
        assertEquals(0, Commands.setWind(running, -5).windLevel)
        assertEquals(63, Commands.setWind(running, 63).windLevel)
    }

    @Test
    fun `mode option writes its own id when on and 0xFF when off`() {
        // Captured live: Silent=1, Hot Pack=2, Cooling First=3, Low Power=4, none=0xFF.
        assertEquals(0x01, Commands.setModeOption(running, ModeOption.SILENT, on = true)!!.modeOptionCode)
        assertEquals(0x04, Commands.setModeOption(running, ModeOption.LOW_POWER, on = true)!!.modeOptionCode)
        assertEquals(0xFF, Commands.setModeOption(running, ModeOption.SILENT, on = false)!!.modeOptionCode)
        assertEquals(0xFF, Commands.setModeOption(running, null, on = false)!!.modeOptionCode)

        // Off (byte 6 = 3) refuses options; running/paused allow them.
        val off = running.withByte(Idx.POWER, Power.OFF.code)
        assertNull(Commands.setModeOption(off, ModeOption.SILENT, on = true))
    }

    @Test
    fun `active option is read back from the state byte`() {
        assertEquals(ModeOption.SILENT, running.withByte(Idx.MODE_OPTION, 1).activeOption)
        assertEquals(ModeOption.HOT_PACK, running.withByte(Idx.MODE_OPTION, 2).activeOption)
        assertEquals(ModeOption.LOW_POWER, running.withByte(Idx.MODE_OPTION, 4).activeOption)
        assertNull(running.withByte(Idx.MODE_OPTION, 0xFF).activeOption)
    }

    @Test
    fun `AI sends 4 but is parsed from 4 or 6`() {
        assertEquals(4, Commands.setMode(running, Mode.AI)!!.modeCode)
        assertEquals(Mode.AI, running.withByte(Idx.MODE, 6).mode)
        assertEquals(Mode.AI, running.withByte(Idx.MODE, 4).mode)
        assertEquals(Mode.COOLING, running.withByte(Idx.MODE, 1).mode)
    }

    @Test
    fun `pause and resume set the power byte`() {
        assertEquals(Power.IDLE.code, Commands.pause(running).powerCode)
        assertEquals(Power.ON.code, Commands.resume(running).powerCode)
    }

    @Test
    fun `light and voice edit their own nibble of byte 9`() {
        // Capture default 0x22 = light-off | voice-off.
        assertEquals(0x12, Commands.setLight(running, on = true).flags)
        assertEquals(0x22, Commands.setLight(running, on = false).flags)
        assertEquals(0x21, Commands.setVoice(running, on = true).flags)
        assertEquals(0x22, Commands.setVoice(running, on = false).flags)

        // Toggling one must not clobber the other.
        val both = Commands.setVoice(Commands.setLight(running, on = true), on = true)
        assertEquals(0x11, both.flags)
        assertTrue(both.lightOn)
        assertTrue(both.voiceOn)
    }

    @Test
    fun `commands only ever change the byte they own`() {
        val before = running.raw
        val after = Commands.setWind(running, 77).raw
        before.indices.forEach { i ->
            if (i != Idx.WIND_LEVEL) assertEquals("byte $i changed", before[i], after[i])
        }
    }

    @Test
    fun `state is immutable across mutations`() {
        val original = running.raw.copyOf()
        Commands.powerOff(running)
        Commands.setWind(running, 5)
        assertArrayEquals(original, running.raw)
    }
}
