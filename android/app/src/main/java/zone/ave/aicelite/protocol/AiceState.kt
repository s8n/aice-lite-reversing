package zone.ave.aicelite.protocol

import androidx.compose.runtime.Immutable

/** `payload[6]` — master power state (PROTOCOL.md §5). */
enum class Power(val code: Int) {
    IDLE(1),
    ON(2),
    OFF(3),
    TURN_ON(4);

    /** True once the compressor/fan is actually running. */
    val isRunning: Boolean get() = this == ON

    companion object {
        fun from(code: Int): Power? = entries.firstOrNull { it.code == code }
    }
}

/**
 * `payload[10]` — operating mode.
 *
 * [code] is what the device *reports*; [sendCode] is what a command *writes*.
 * They match for all modes except AI, where the button writes 4 but the device
 * echoes 6 (captured live from the official app).
 */
enum class Mode(val code: Int, val sendCode: Int, val label: String) {
    COOLING(1, 1, "Cooling"),
    HEATING(2, 2, "Heating"),
    FAN(3, 3, "Wind"),
    AI(6, 4, "PID Mode");

    /**
     * Target-temperature bounds (°C) the UI clamps to. Cooling 16–30 and
     * heating 40–50 confirmed by the operator; wind/fan has no target temp.
     * AI's bounds are still unverified (kept at 16–30).
     */
    val tempRange: IntRange?
        get() = when (this) {
            COOLING -> 16..30
            HEATING -> 40..50
            AI -> 16..30
            FAN -> null
        }

    /** Heating doesn't blow — the wind control is a no-op there (confirmed by operator). */
    val hasWind: Boolean get() = this != HEATING

    companion object {
        /** Map a device-reported (or command) code to a mode; 4 and 6 both mean AI. */
        fun from(code: Int): Mode? = when (code) {
            4 -> AI
            else -> entries.firstOrNull { it.code == code }
        }
    }
}

/**
 * `payload[14]` — mode option selector.
 *
 * Each option has a distinct on-the-wire id (captured live from the official
 * app), so the active option is readable straight from the byte — no
 * client-side tracking needed. Turning an option off writes [NONE_CODE].
 * (The old `0xFE`/`0x02`/… scheme from PROTOCOL.md §5b was wrong; writing Hot
 * Pack's `0x02` for "silent off" is what made the device switch to heating.)
 */
enum class ModeOption(val code: Int, val label: String) {
    SILENT(0x01, "Silent"),
    HOT_PACK(0x02, "Hot Pack"),
    COOLING_FIRST(0x03, "Cooling First"),
    LOW_POWER(0x04, "Low Power");

    companion object {
        const val NONE_CODE = 0xFF
        fun from(code: Int): ModeOption? = entries.firstOrNull { it.code == code }
    }
}

/** `payload[15]` — display unit. */
enum class TempUnit(val code: Int, val suffix: String) {
    CELSIUS(1, "°C"),
    FAHRENHEIT(2, "°F");

    fun format(celsius: Int): Int =
        if (this == FAHRENHEIT) Math.round(celsius * 9f / 5f) + 32 else celsius

    companion object {
        fun from(code: Int): TempUnit = entries.firstOrNull { it.code == code } ?: CELSIUS
    }
}

/** Bit flags in `payload[9]` (§5a). */
object Flags {
    const val LIGHT_ON = 0x10
    const val LIGHT_OFF = 0x20
    const val VOICE_ON = 0x01
    const val VOICE_OFF = 0x02
}

/** Payload indices, named. */
object Idx {
    const val POWER = 6
    const val CHARGE_STATUS = 7
    const val BATTERY = 8
    const val FLAGS = 9
    const val MODE = 10
    const val AMBIENT_TEMP = 11
    const val TARGET_TEMP = 12
    const val WIND_LEVEL = 13
    const val MODE_OPTION = 14
    const val TEMP_UNIT = 15
}

const val WIND_MIN = 0
const val WIND_MAX = 100

/**
 * The `payload[8]` battery gauge is coarse — one count per bar, on a 4-bar meter:
 * `2`=empty … `6`=full (observed: `4`=2 bars, `5`=3 bars, stable for hours, so it
 * quantizes to bars rather than a fine %). Percentage is therefore per-bar
 * (0/25/50/75/100), not continuous.
 */
const val BATTERY_RAW_EMPTY = 2
const val BATTERY_RAW_FULL = 6

/**
 * A decoded 16-byte state buffer. The device and the app exchange the *entire*
 * state on every change; a command is just this buffer with one byte edited and
 * the whole thing re-sent (§5). Always keep [raw] around — bytes 4 and 5 are
 * still unnamed and must be echoed back verbatim.
 */
@Immutable
class AiceState(raw: ByteArray) {

    val raw: ByteArray = raw.copyOf()

    init {
        require(raw.size >= AiceCodec.STATE_PAYLOAD_SIZE) {
            "state payload must be ${AiceCodec.STATE_PAYLOAD_SIZE} bytes, got ${raw.size}"
        }
    }

    private fun u(i: Int) = raw[i].toInt() and 0xFF

    val powerCode: Int get() = u(Idx.POWER)
    val power: Power? get() = Power.from(powerCode)
    val modeCode: Int get() = u(Idx.MODE)
    val mode: Mode? get() = Mode.from(modeCode)
    val ambientTempC: Int get() = u(Idx.AMBIENT_TEMP)
    val targetTempC: Int get() = u(Idx.TARGET_TEMP)
    val windLevel: Int get() = u(Idx.WIND_LEVEL)
    val modeOptionCode: Int get() = u(Idx.MODE_OPTION)
    val flags: Int get() = u(Idx.FLAGS)
    val unit: TempUnit get() = TempUnit.from(u(Idx.TEMP_UNIT))

    /** `payload[6]`: 1 = paused (idle), 2 = running, 3 = off. */
    val isRunning: Boolean get() = powerCode == Power.ON.code
    val isPaused: Boolean get() = powerCode == Power.IDLE.code
    val isOff: Boolean get() = powerCode == Power.OFF.code

    /** The device is powered (running or paused), as opposed to off. */
    val isPowered: Boolean get() = isRunning || isPaused

    val activeOption: ModeOption? get() = ModeOption.from(modeOptionCode)
    val hasModeOption: Boolean get() = activeOption != null

    val lightOn: Boolean get() = flags and Flags.LIGHT_ON != 0
    val voiceOn: Boolean get() = flags and Flags.VOICE_ON != 0

    /** `payload[7]`: 1 = on battery, ≥2 = plugged/charging (3 = steady charge, 2 = transient). */
    val isCharging: Boolean get() = u(Idx.CHARGE_STATUS) >= 2

    /** `payload[8]`: raw battery gauge (small integer, ~one count per bar). */
    val batteryRaw: Int get() = u(Idx.BATTERY)

    /** Battery bars, 0–4. */
    val batteryBars: Int get() = (batteryRaw - BATTERY_RAW_EMPTY).coerceIn(0, 4)

    /** Coarse percentage from the bar count: 0/25/50/75/100. */
    val batteryPercent: Int get() =
        ((batteryRaw - BATTERY_RAW_EMPTY) * 100 / (BATTERY_RAW_FULL - BATTERY_RAW_EMPTY)).coerceIn(0, 100)

    /** Fan mode has no target temperature. */
    val supportsTargetTemp: Boolean get() = mode?.tempRange != null

    /** Heating has no usable wind control. */
    val supportsWind: Boolean get() = mode?.hasWind ?: true

    fun mutate(block: (ByteArray) -> Unit): AiceState = AiceState(raw.copyOf().also(block))

    fun withByte(index: Int, value: Int): AiceState = mutate { it[index] = value.toByte() }

    fun encode(): ByteArray = AiceCodec.encode(raw)

    override fun equals(other: Any?): Boolean =
        this === other || (other is AiceState && raw.contentEquals(other.raw))

    override fun hashCode(): Int = raw.contentHashCode()

    override fun toString(): String =
        "AiceState(power=$powerCode mode=$modeCode ambient=$ambientTempC target=$targetTempC " +
            "wind=$windLevel opt=$modeOptionCode flags=0x%02X)".format(flags)

    companion object {
        /** Decode a 20-byte notification frame into state, or null if it isn't one. */
        fun fromFrame(frame: ByteArray): AiceState? {
            val payload = AiceCodec.decode(frame) ?: return null
            if (payload.size < AiceCodec.STATE_PAYLOAD_SIZE) return null
            return AiceState(payload.copyOf(AiceCodec.STATE_PAYLOAD_SIZE))
        }
    }
}

/**
 * Command builders (§5, "Command operations"). Each returns a *new* state with
 * one byte edited; the caller sends the whole buffer. A builder returns null
 * when the device would reject the command anyway, so the UI can grey out the
 * control instead of firing a no-op frame.
 */
object Commands {

    /** Turn on: 2 if already running, else 4 (turn-on). */
    fun powerOn(s: AiceState): AiceState =
        s.withByte(Idx.POWER, if (s.isRunning) Power.ON.code else Power.TURN_ON.code)

    /** Turn off completely (`payload[6]=3`). */
    fun powerOff(s: AiceState): AiceState = s.withByte(Idx.POWER, Power.OFF.code)

    /** Pause airflow while staying powered (`payload[6]=1`). */
    fun pause(s: AiceState): AiceState = s.withByte(Idx.POWER, Power.IDLE.code)

    /** Resume from pause (`payload[6]=2`). */
    fun resume(s: AiceState): AiceState = s.withByte(Idx.POWER, Power.ON.code)

    /**
     * Set operating mode. Writes the mode's [Mode.sendCode] (AI = 4, not 6) and
     * snaps the target temperature into the new mode's range (e.g. cooling's 22 °C
     * clamps up to heating's 30 °C). Refused while off.
     */
    fun setMode(s: AiceState, mode: Mode): AiceState? {
        if (s.isOff) return null
        val withMode = s.withByte(Idx.MODE, mode.sendCode)
        val range = mode.tempRange ?: return withMode
        return withMode.withByte(Idx.TARGET_TEMP, s.targetTempC.coerceIn(range))
    }

    /** Temperature step: requires a running device and a mode with a target temp. */
    fun adjustTemp(s: AiceState, delta: Int): AiceState? {
        if (!s.isRunning) return null
        val range = s.mode?.tempRange ?: return null
        val next = (s.targetTempC + delta).coerceIn(range)
        return if (next == s.targetTempC) null else s.withByte(Idx.TARGET_TEMP, next)
    }

    fun setWind(s: AiceState, level: Int): AiceState =
        s.withByte(Idx.WIND_LEVEL, level.coerceIn(WIND_MIN, WIND_MAX))

    /**
     * Mode options: ON writes the option's own id (Silent=1 … Low Power=4), OFF
     * writes [ModeOption.NONE_CODE] (0xFF). Only available while powered. Hot Pack
     * and Cooling First make the device change mode on its own.
     */
    fun setModeOption(s: AiceState, option: ModeOption?, on: Boolean): AiceState? {
        if (s.isOff) return null
        val code = if (option != null && on) option.code else ModeOption.NONE_CODE
        return s.withByte(Idx.MODE_OPTION, code)
    }

    /** UNVERIFIED: assumed to ride along in the state buffer like every other byte. */
    fun setUnit(s: AiceState, unit: TempUnit): AiceState = s.withByte(Idx.TEMP_UNIT, unit.code)

    /** Light toggle, as a state-buffer edit of the flags nibble (§5a). */
    fun setLight(s: AiceState, on: Boolean): AiceState =
        s.withByte(Idx.FLAGS, (s.flags and 0x0F) or if (on) Flags.LIGHT_ON else Flags.LIGHT_OFF)

    /** Voice toggle, as a state-buffer edit of the flags nibble (§5a). */
    fun setVoice(s: AiceState, on: Boolean): AiceState =
        s.withByte(Idx.FLAGS, (s.flags and 0xF0) or if (on) Flags.VOICE_ON else Flags.VOICE_OFF)
}
