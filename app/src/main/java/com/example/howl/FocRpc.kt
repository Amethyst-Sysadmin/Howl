package com.example.howl

import java.io.ByteArrayOutputStream

/**
 * Kotlin implementation of the FOC Stim RPC protocol and all its messages, constants etc.
 * Derived by an LLM from the protobuf generated code (as of FOC firmware 2.0.3).
 * We used a custom implementation here mainly to avoid importing the heavyweight Kotlin and
 * Java protobuf libraries. This reduces app size bloat and improves build times for developers.
 *
 * This replaces the protobuf gencode (and the protobuf-java/runtime dependencies)
 * with a small, dependency-free wire codec. The types, field numbers, enum values
 * and proto3 presence rules match the upstream `focstim_rpc.proto` schema exactly.
 *
 * Wire format notes:
 *  - Proto3 default values (0, false, "", empty bytes, enum value 0) are
 *    omitted when encoding.
 *  - Message-typed fields (including oneof members) are nullable: null means
 *    absent, non-null means present (even when the nested message is empty).
 *  - Unknown fields are skipped when decoding so newer firmware can add fields
 *    without breaking older clients.
 */
object FocRpc {

    // ── Enums ─────────────────────────────────────────────────────────────

    enum class AxisType(val value: Int) {
        AXIS_UNKNOWN(0),
        AXIS_POSITION_ALPHA(1),
        AXIS_POSITION_BETA(2),
        AXIS_POSITION_GAMMA(3),
        AXIS_WAVEFORM_AMPLITUDE_AMPS(11),
        AXIS_CARRIER_FREQUENCY_HZ(20),
        AXIS_PULSE_WIDTH_IN_CYCLES(21),
        AXIS_PULSE_RISE_TIME_CYCLES(22),
        AXIS_PULSE_FREQUENCY_HZ(23),
        AXIS_PULSE_INTERVAL_RANDOM_PERCENT(24),
        AXIS_CALIBRATION_3_CENTER(30),
        AXIS_CALIBRATION_3_UP(31),
        AXIS_CALIBRATION_3_LEFT(32),
        AXIS_CALIBRATION_4_CENTER(40),
        AXIS_CALIBRATION_4_A(41),
        AXIS_CALIBRATION_4_B(42),
        AXIS_CALIBRATION_4_C(43),
        AXIS_CALIBRATION_4_D(44),
        AXIS_CALIBRATION_4_REDUCTION_IN_CENTER(45),
        AXIS_ELECTRODE_1_POWER(50),
        AXIS_ELECTRODE_2_POWER(51),
        AXIS_ELECTRODE_3_POWER(52),
        AXIS_ELECTRODE_4_POWER(53),
        ;

        companion object {
            fun fromValue(value: Int): AxisType? = entries.firstOrNull { it.value == value }
        }
    }

    enum class BoardIdentifier(val value: Int) {
        BOARD_UNKNOWN(0),
        BOARD_B_G431B_ESC1(1),
        BOARD_FOCSTIM_V4(2),
        ;

        companion object {
            fun fromValue(value: Int): BoardIdentifier? = entries.firstOrNull { it.value == value }
        }
    }

    enum class OutputMode(val value: Int) {
        OUTPUT_UNKNOWN(0),
        OUTPUT_THREEPHASE(2),
        OUTPUT_FOURPHASE(3),
        OUTPUT_FOURPHASE_INDIVIDUAL_ELECTRODES(4),
        ;

        companion object {
            fun fromValue(value: Int): OutputMode? = entries.firstOrNull { it.value == value }
        }
    }

    enum class StreamingMode(val value: Int) {
        STREAMING_UNKNOWN(0),
        STREAMING_MOVETO(1),
        STREAMING_BUFFERED(2),
        ;

        companion object {
            fun fromValue(value: Int): StreamingMode? = entries.firstOrNull { it.value == value }
        }
    }

    enum class Errors(val value: Int) {
        ERROR_UNKNOWN(0),
        ERROR_OUTPUT_NOT_SUPPORTED(1),
        ERROR_UNKNOWN_REQUEST(2),
        ERROR_POWER_NOT_PRESENT(3),
        ERROR_ALREADY_PLAYING(4),
        ;

        companion object {
            fun fromValue(value: Int): Errors? = entries.firstOrNull { it.value == value }
        }
    }

    enum class ButtonState(val value: Int) {
        BUTTON_UNKNOWN(0),
        BUTTON_DOWN(1),
        BUTTON_UP(2),
        ;

        companion object {
            fun fromValue(value: Int): ButtonState? = entries.firstOrNull { it.value == value }
        }
    }

    // ── messages.proto ────────────────────────────────────────────────────

    data class FirmwareVersion(
        val major: Int = 0,
        val minor: Int = 0,
        val revision: Int = 0,
        val branch: String = "",
        val comment: String = "",
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeUInt32(1, major)
            writeUInt32(2, minor)
            writeUInt32(3, revision)
            writeString(4, branch)
            writeString(5, comment)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): FirmwareVersion {
                var major = 0
                var minor = 0
                var revision = 0
                var branch = ""
                var comment = ""
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> major = readUInt32().toInt()
                        2 -> minor = readUInt32().toInt()
                        3 -> revision = readUInt32().toInt()
                        4 -> branch = readString()
                        5 -> comment = readString()
                        else -> skip(wireType)
                    }
                }
                return FirmwareVersion(major, minor, revision, branch, comment)
            }
        }
    }

    data object RequestFirmwareVersion {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): RequestFirmwareVersion = this
    }

    data class ResponseFirmwareVersion(
        val board: BoardIdentifier = BoardIdentifier.BOARD_UNKNOWN,
        val stm32FirmwareVersion2: FirmwareVersion? = null,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeEnum(1, board.value)
            stm32FirmwareVersion2?.let { writeMessage(3, it.toByteArray()) }
        }

        companion object {
            fun parseFrom(bytes: ByteArray): ResponseFirmwareVersion {
                var board = BoardIdentifier.BOARD_UNKNOWN
                var stm32FirmwareVersion2: FirmwareVersion? = null
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> BoardIdentifier.fromValue(readUInt32().toInt())?.let { board = it }
                        3 -> stm32FirmwareVersion2 = FirmwareVersion.parseFrom(readBytes())
                        else -> skip(wireType)
                    }
                }
                return ResponseFirmwareVersion(board, stm32FirmwareVersion2)
            }
        }
    }

    data object RequestCapabilitiesGet {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): RequestCapabilitiesGet = this
    }

    data class ResponseCapabilitiesGet(
        val threephase: Boolean = false,
        val fourphase: Boolean = false,
        val battery: Boolean = false,
        val deviceVolume: Boolean = false,
        val maximumWaveformAmplitudeAmps: Float = 0f,
        val lsm6Dsox: Boolean = false,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeBool(1, threephase)
            writeBool(2, fourphase)
            writeBool(3, battery)
            writeBool(4, deviceVolume)
            writeFloat(5, maximumWaveformAmplitudeAmps)
            writeBool(6, lsm6Dsox)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): ResponseCapabilitiesGet {
                var threephase = false
                var fourphase = false
                var battery = false
                var deviceVolume = false
                var maximumWaveformAmplitudeAmps = 0f
                var lsm6Dsox = false
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> threephase = readBool()
                        2 -> fourphase = readBool()
                        3 -> battery = readBool()
                        4 -> deviceVolume = readBool()
                        5 -> maximumWaveformAmplitudeAmps = readFloat()
                        6 -> lsm6Dsox = readBool()
                        else -> skip(wireType)
                    }
                }
                return ResponseCapabilitiesGet(
                    threephase,
                    fourphase,
                    battery,
                    deviceVolume,
                    maximumWaveformAmplitudeAmps,
                    lsm6Dsox,
                )
            }
        }
    }

    data class RequestSignalStart(
        val mode: OutputMode = OutputMode.OUTPUT_UNKNOWN,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeEnum(1, mode.value)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): RequestSignalStart {
                var mode = OutputMode.OUTPUT_UNKNOWN
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> OutputMode.fromValue(readUInt32().toInt())?.let { mode = it }
                        else -> skip(wireType)
                    }
                }
                return RequestSignalStart(mode)
            }
        }
    }

    data object ResponseSignalStart {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): ResponseSignalStart = this
    }

    data object RequestSignalStop {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): RequestSignalStop = this
    }

    data object ResponseSignalStop {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): ResponseSignalStop = this
    }

    data object RequestModeSet {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): RequestModeSet = this
    }

    data object ResponseModeSet {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): ResponseModeSet = this
    }

    data class RequestAxisMoveTo(
        val axis: AxisType = AxisType.AXIS_UNKNOWN,
        val value: Float = 0f,
        val interval: Int = 0,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeEnum(1, axis.value)
            writeFloat(3, value)
            writeUInt32(4, interval)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): RequestAxisMoveTo {
                var axis = AxisType.AXIS_UNKNOWN
                var value = 0f
                var interval = 0
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> AxisType.fromValue(readUInt32().toInt())?.let { axis = it }
                        3 -> value = readFloat()
                        4 -> interval = readUInt32().toInt()
                        else -> skip(wireType)
                    }
                }
                return RequestAxisMoveTo(axis, value, interval)
            }
        }
    }

    data object ResponseAxisMoveTo {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): ResponseAxisMoveTo = this
    }

    data class RequestAxisSet(
        val axis: AxisType = AxisType.AXIS_UNKNOWN,
        val timestampMs: Long = 0L,
        val value: Float = 0f,
        val clear: Boolean = false,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeEnum(1, axis.value)
            writeFixed32(2, timestampMs)
            writeFloat(3, value)
            writeBool(4, clear)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): RequestAxisSet {
                var axis = AxisType.AXIS_UNKNOWN
                var timestampMs = 0L
                var value = 0f
                var clear = false
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> AxisType.fromValue(readUInt32().toInt())?.let { axis = it }
                        2 -> timestampMs = readFixed32()
                        3 -> value = readFloat()
                        4 -> clear = readBool()
                        else -> skip(wireType)
                    }
                }
                return RequestAxisSet(axis, timestampMs, value, clear)
            }
        }
    }

    data object ResponseAxisSet {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): ResponseAxisSet = this
    }

    data class RequestTimestampSet(
        val timestampMs: Long = 0L,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeUInt64(1, timestampMs)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): RequestTimestampSet {
                var timestampMs = 0L
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> timestampMs = readUInt64()
                        else -> skip(wireType)
                    }
                }
                return RequestTimestampSet(timestampMs)
            }
        }
    }

    data class ResponseTimestampSet(
        val offsetMs: Long = 0L,
        val changeMs: Long = 0L,
        val errorMs: Long = 0L,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeInt64(1, offsetMs)
            writeSInt64(2, changeMs)
            writeSInt64(3, errorMs)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): ResponseTimestampSet {
                var offsetMs = 0L
                var changeMs = 0L
                var errorMs = 0L
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> offsetMs = readInt64()
                        2 -> changeMs = readSInt64()
                        3 -> errorMs = readSInt64()
                        else -> skip(wireType)
                    }
                }
                return ResponseTimestampSet(offsetMs, changeMs, errorMs)
            }
        }
    }

    data object RequestTimestampGet {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): RequestTimestampGet = this
    }

    data class ResponseTimestampGet(
        val timestampMs: Long = 0L,
        val unixTimestampMs: Long = 0L,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeFixed32(1, timestampMs)
            writeUInt64(2, unixTimestampMs)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): ResponseTimestampGet {
                var timestampMs = 0L
                var unixTimestampMs = 0L
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> timestampMs = readFixed32()
                        2 -> unixTimestampMs = readUInt64()
                        else -> skip(wireType)
                    }
                }
                return ResponseTimestampGet(timestampMs, unixTimestampMs)
            }
        }
    }

    data class RequestWifiParametersSet(
        val ssid: ByteArray = ByteArray(0),
        val password: ByteArray = ByteArray(0),
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeBytes(1, ssid)
            writeBytes(2, password)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): RequestWifiParametersSet {
                var ssid = ByteArray(0)
                var password = ByteArray(0)
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> ssid = readBytes()
                        2 -> password = readBytes()
                        else -> skip(wireType)
                    }
                }
                return RequestWifiParametersSet(ssid, password)
            }
        }
    }

    data object ResponseWifiParametersSet {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): ResponseWifiParametersSet = this
    }

    data object RequestWifiIPGet {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): RequestWifiIPGet = this
    }

    data class ResponseWifiIPGet(
        val ip: Long = 0L,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeUInt32(1, ip)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): ResponseWifiIPGet {
                var ip = 0L
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> ip = readUInt32()
                        else -> skip(wireType)
                    }
                }
                return ResponseWifiIPGet(ip)
            }
        }
    }

    data class RequestLockDeviceVolume(
        val lock: Boolean = false,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeBool(1, lock)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): RequestLockDeviceVolume {
                var lock = false
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> lock = readBool()
                        else -> skip(wireType)
                    }
                }
                return RequestLockDeviceVolume(lock)
            }
        }
    }

    data object ResponseLockDeviceVolume {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): ResponseLockDeviceVolume = this
    }

    data class RequestLSM6DSOXStart(
        val imuSamplerate: Float = 0f,
        val accFullscale: Float = 0f,
        val gyrFullscale: Float = 0f,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeFloat(1, imuSamplerate)
            writeFloat(2, accFullscale)
            writeFloat(3, gyrFullscale)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): RequestLSM6DSOXStart {
                var imuSamplerate = 0f
                var accFullscale = 0f
                var gyrFullscale = 0f
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> imuSamplerate = readFloat()
                        2 -> accFullscale = readFloat()
                        3 -> gyrFullscale = readFloat()
                        else -> skip(wireType)
                    }
                }
                return RequestLSM6DSOXStart(imuSamplerate, accFullscale, gyrFullscale)
            }
        }
    }

    data class ResponseLSM6DSOXStart(
        val accSensitivity: Float = 0f,
        val gyrSensitivity: Float = 0f,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeFloat(1, accSensitivity)
            writeFloat(2, gyrSensitivity)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): ResponseLSM6DSOXStart {
                var accSensitivity = 0f
                var gyrSensitivity = 0f
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> accSensitivity = readFloat()
                        2 -> gyrSensitivity = readFloat()
                        else -> skip(wireType)
                    }
                }
                return ResponseLSM6DSOXStart(accSensitivity, gyrSensitivity)
            }
        }
    }

    data object RequestLSM6DSOXStop {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): RequestLSM6DSOXStop = this
    }

    data object ResponseLSM6DSOXStop {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): ResponseLSM6DSOXStop = this
    }

    data object RequestDebugStm32DeepSleep {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): RequestDebugStm32DeepSleep = this
    }

    data object ResponseDebugStm32DeepSleep {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): ResponseDebugStm32DeepSleep = this
    }

    data object RequestDebugEnterBootloader {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): RequestDebugEnterBootloader = this
    }

    // ── notifications.proto ───────────────────────────────────────────────

    data object NotificationBoot {
        fun toByteArray(): ByteArray = ByteArray(0)

        fun parseFrom(bytes: ByteArray): NotificationBoot = this
    }

    data class NotificationDeviceVolume(
        val volume: Float = 0f,
        val locked: Boolean = false,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeFloat(1, volume)
            writeBool(2, locked)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): NotificationDeviceVolume {
                var volume = 0f
                var locked = false
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> volume = readFloat()
                        2 -> locked = readBool()
                        else -> skip(wireType)
                    }
                }
                return NotificationDeviceVolume(volume, locked)
            }
        }
    }

    data class NotificationButtonPress(
        val state: ButtonState = ButtonState.BUTTON_UNKNOWN,
        val timestampMs: Long = 0L,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeEnum(1, state.value)
            writeUInt32(2, timestampMs)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): NotificationButtonPress {
                var state = ButtonState.BUTTON_UNKNOWN
                var timestampMs = 0L
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> ButtonState.fromValue(readUInt32().toInt())?.let { state = it }
                        2 -> timestampMs = readUInt32()
                        else -> skip(wireType)
                    }
                }
                return NotificationButtonPress(state, timestampMs)
            }
        }
    }

    data class NotificationCurrents(
        val rmsA: Float = 0f,
        val rmsB: Float = 0f,
        val rmsC: Float = 0f,
        val rmsD: Float = 0f,
        val peakA: Float = 0f,
        val peakB: Float = 0f,
        val peakC: Float = 0f,
        val peakD: Float = 0f,
        val outputPower: Float = 0f,
        val outputPowerSkin: Float = 0f,
        val peakCmd: Float = 0f,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeFloat(1, rmsA)
            writeFloat(2, rmsB)
            writeFloat(3, rmsC)
            writeFloat(4, rmsD)
            writeFloat(5, peakA)
            writeFloat(6, peakB)
            writeFloat(7, peakC)
            writeFloat(8, peakD)
            writeFloat(9, outputPower)
            writeFloat(10, outputPowerSkin)
            writeFloat(11, peakCmd)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): NotificationCurrents {
                var rmsA = 0f
                var rmsB = 0f
                var rmsC = 0f
                var rmsD = 0f
                var peakA = 0f
                var peakB = 0f
                var peakC = 0f
                var peakD = 0f
                var outputPower = 0f
                var outputPowerSkin = 0f
                var peakCmd = 0f
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> rmsA = readFloat()
                        2 -> rmsB = readFloat()
                        3 -> rmsC = readFloat()
                        4 -> rmsD = readFloat()
                        5 -> peakA = readFloat()
                        6 -> peakB = readFloat()
                        7 -> peakC = readFloat()
                        8 -> peakD = readFloat()
                        9 -> outputPower = readFloat()
                        10 -> outputPowerSkin = readFloat()
                        11 -> peakCmd = readFloat()
                        else -> skip(wireType)
                    }
                }
                return NotificationCurrents(
                    rmsA, rmsB, rmsC, rmsD,
                    peakA, peakB, peakC, peakD,
                    outputPower, outputPowerSkin, peakCmd,
                )
            }
        }
    }

    data class NotificationOutputResistance(
        val resistanceA: Float = 0f,
        val reluctanceA: Float = 0f,
        val resistanceB: Float = 0f,
        val reluctanceB: Float = 0f,
        val resistanceC: Float = 0f,
        val reluctanceC: Float = 0f,
        val resistanceD: Float = 0f,
        val reluctanceD: Float = 0f,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeFloat(1, resistanceA)
            writeFloat(2, reluctanceA)
            writeFloat(3, resistanceB)
            writeFloat(4, reluctanceB)
            writeFloat(5, resistanceC)
            writeFloat(6, reluctanceC)
            writeFloat(7, resistanceD)
            writeFloat(8, reluctanceD)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): NotificationOutputResistance {
                var resistanceA = 0f
                var reluctanceA = 0f
                var resistanceB = 0f
                var reluctanceB = 0f
                var resistanceC = 0f
                var reluctanceC = 0f
                var resistanceD = 0f
                var reluctanceD = 0f
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> resistanceA = readFloat()
                        2 -> reluctanceA = readFloat()
                        3 -> resistanceB = readFloat()
                        4 -> reluctanceB = readFloat()
                        5 -> resistanceC = readFloat()
                        6 -> reluctanceC = readFloat()
                        7 -> resistanceD = readFloat()
                        8 -> reluctanceD = readFloat()
                        else -> skip(wireType)
                    }
                }
                return NotificationOutputResistance(
                    resistanceA, reluctanceA,
                    resistanceB, reluctanceB,
                    resistanceC, reluctanceC,
                    resistanceD, reluctanceD,
                )
            }
        }
    }

    data class NotificationSkinResistance(
        val resistanceA: Float = 0f,
        val reluctanceA: Float = 0f,
        val resistanceB: Float = 0f,
        val reluctanceB: Float = 0f,
        val resistanceC: Float = 0f,
        val reluctanceC: Float = 0f,
        val resistanceD: Float = 0f,
        val reluctanceD: Float = 0f,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeFloat(1, resistanceA)
            writeFloat(2, reluctanceA)
            writeFloat(3, resistanceB)
            writeFloat(4, reluctanceB)
            writeFloat(5, resistanceC)
            writeFloat(6, reluctanceC)
            writeFloat(7, resistanceD)
            writeFloat(8, reluctanceD)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): NotificationSkinResistance {
                var resistanceA = 0f
                var reluctanceA = 0f
                var resistanceB = 0f
                var reluctanceB = 0f
                var resistanceC = 0f
                var reluctanceC = 0f
                var resistanceD = 0f
                var reluctanceD = 0f
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> resistanceA = readFloat()
                        2 -> reluctanceA = readFloat()
                        3 -> resistanceB = readFloat()
                        4 -> reluctanceB = readFloat()
                        5 -> resistanceC = readFloat()
                        6 -> reluctanceC = readFloat()
                        7 -> resistanceD = readFloat()
                        8 -> reluctanceD = readFloat()
                        else -> skip(wireType)
                    }
                }
                return NotificationSkinResistance(
                    resistanceA, reluctanceA,
                    resistanceB, reluctanceB,
                    resistanceC, reluctanceC,
                    resistanceD, reluctanceD,
                )
            }
        }
    }

    data class SystemStatsESC1(
        val tempStm32: Float = 0f,
        val tempBoard: Float = 0f,
        val vBus: Float = 0f,
        val vRef: Float = 0f,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeFloat(1, tempStm32)
            writeFloat(2, tempBoard)
            writeFloat(3, vBus)
            writeFloat(4, vRef)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): SystemStatsESC1 {
                var tempStm32 = 0f
                var tempBoard = 0f
                var vBus = 0f
                var vRef = 0f
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> tempStm32 = readFloat()
                        2 -> tempBoard = readFloat()
                        3 -> vBus = readFloat()
                        4 -> vRef = readFloat()
                        else -> skip(wireType)
                    }
                }
                return SystemStatsESC1(tempStm32, tempBoard, vBus, vRef)
            }
        }
    }

    data class SystemStatsFocstimV3(
        val tempStm32: Float = 0f,
        val vSysMin: Float = 0f,
        val vSysMax: Float = 0f,
        val vRef: Float = 0f,
        val vBoostMin: Float = 0f,
        val vBoostMax: Float = 0f,
        val boostDutyCycle: Float = 0f,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeFloat(1, tempStm32)
            writeFloat(2, vSysMin)
            writeFloat(3, vRef)
            writeFloat(4, vBoostMin)
            writeFloat(5, boostDutyCycle)
            writeFloat(6, vSysMax)
            writeFloat(7, vBoostMax)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): SystemStatsFocstimV3 {
                var tempStm32 = 0f
                var vSysMin = 0f
                var vSysMax = 0f
                var vRef = 0f
                var vBoostMin = 0f
                var vBoostMax = 0f
                var boostDutyCycle = 0f
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> tempStm32 = readFloat()
                        2 -> vSysMin = readFloat()
                        3 -> vRef = readFloat()
                        4 -> vBoostMin = readFloat()
                        5 -> boostDutyCycle = readFloat()
                        6 -> vSysMax = readFloat()
                        7 -> vBoostMax = readFloat()
                        else -> skip(wireType)
                    }
                }
                return SystemStatsFocstimV3(
                    tempStm32, vSysMin, vSysMax, vRef, vBoostMin, vBoostMax, boostDutyCycle,
                )
            }
        }
    }

    data class NotificationSystemStats(
        val esc1: SystemStatsESC1? = null,
        val focstimv3: SystemStatsFocstimV3? = null,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            esc1?.let { writeMessage(1, it.toByteArray()) }
            focstimv3?.let { writeMessage(2, it.toByteArray()) }
        }

        companion object {
            fun parseFrom(bytes: ByteArray): NotificationSystemStats {
                var esc1: SystemStatsESC1? = null
                var focstimv3: SystemStatsFocstimV3? = null
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> esc1 = SystemStatsESC1.parseFrom(readBytes())
                        2 -> focstimv3 = SystemStatsFocstimV3.parseFrom(readBytes())
                        else -> skip(wireType)
                    }
                }
                return NotificationSystemStats(esc1, focstimv3)
            }
        }
    }

    data class NotificationSignalStats(
        val actualPulseFrequency: Float = 0f,
        val vDrive: Float = 0f,
        val transformerUtilization: Float = 0f,
        val voltageUtilization: Float = 0f,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeFloat(1, actualPulseFrequency)
            writeFloat(2, vDrive)
            writeFloat(3, transformerUtilization)
            writeFloat(4, voltageUtilization)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): NotificationSignalStats {
                var actualPulseFrequency = 0f
                var vDrive = 0f
                var transformerUtilization = 0f
                var voltageUtilization = 0f
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> actualPulseFrequency = readFloat()
                        2 -> vDrive = readFloat()
                        3 -> transformerUtilization = readFloat()
                        4 -> voltageUtilization = readFloat()
                        else -> skip(wireType)
                    }
                }
                return NotificationSignalStats(
                    actualPulseFrequency, vDrive, transformerUtilization, voltageUtilization,
                )
            }
        }
    }

    data class NotificationBattery(
        val batteryVoltage: Float = 0f,
        val batteryChargeRateWatt: Float = 0f,
        val batterySoc: Float = 0f,
        val wallPowerPresent: Boolean = false,
        val chipTemperature: Float = 0f,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeFloat(1, batteryVoltage)
            writeFloat(2, batteryChargeRateWatt)
            writeFloat(3, batterySoc)
            writeBool(4, wallPowerPresent)
            writeFloat(5, chipTemperature)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): NotificationBattery {
                var batteryVoltage = 0f
                var batteryChargeRateWatt = 0f
                var batterySoc = 0f
                var wallPowerPresent = false
                var chipTemperature = 0f
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> batteryVoltage = readFloat()
                        2 -> batteryChargeRateWatt = readFloat()
                        3 -> batterySoc = readFloat()
                        4 -> wallPowerPresent = readBool()
                        5 -> chipTemperature = readFloat()
                        else -> skip(wireType)
                    }
                }
                return NotificationBattery(
                    batteryVoltage, batteryChargeRateWatt, batterySoc, wallPowerPresent, chipTemperature,
                )
            }
        }
    }

    data class NotificationLSM6DSOX(
        val accX: Int = 0,
        val accY: Int = 0,
        val accZ: Int = 0,
        val gyrX: Int = 0,
        val gyrY: Int = 0,
        val gyrZ: Int = 0,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeSInt32(1, accX)
            writeSInt32(2, accY)
            writeSInt32(3, accZ)
            writeSInt32(4, gyrX)
            writeSInt32(5, gyrY)
            writeSInt32(6, gyrZ)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): NotificationLSM6DSOX {
                var accX = 0
                var accY = 0
                var accZ = 0
                var gyrX = 0
                var gyrY = 0
                var gyrZ = 0
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> accX = readSInt32()
                        2 -> accY = readSInt32()
                        3 -> accZ = readSInt32()
                        4 -> gyrX = readSInt32()
                        5 -> gyrY = readSInt32()
                        6 -> gyrZ = readSInt32()
                        else -> skip(wireType)
                    }
                }
                return NotificationLSM6DSOX(accX, accY, accZ, gyrX, gyrY, gyrZ)
            }
        }
    }

    data class NotificationPressure(
        val pressure: Float = 0f,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeFloat(1, pressure)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): NotificationPressure {
                var pressure = 0f
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> pressure = readFloat()
                        else -> skip(wireType)
                    }
                }
                return NotificationPressure(pressure)
            }
        }
    }

    data class NotificationDebugString(
        val message: String = "",
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeString(1, message)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): NotificationDebugString {
                var message = ""
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> message = readString()
                        else -> skip(wireType)
                    }
                }
                return NotificationDebugString(message)
            }
        }
    }

    data class NotificationDebugAS5311(
        val raw: Int = 0,
        val tracked: Int = 0,
        val flags: Int = 0,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeInt32(1, raw)
            writeSInt32(2, tracked)
            writeInt32(3, flags)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): NotificationDebugAS5311 {
                var raw = 0
                var tracked = 0
                var flags = 0
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> raw = readInt32()
                        2 -> tracked = readSInt32()
                        3 -> flags = readInt32()
                        else -> skip(wireType)
                    }
                }
                return NotificationDebugAS5311(raw, tracked, flags)
            }
        }
    }

    data class NotificationDebugEdging(
        val fullPowerThreshold: Float = 0f,
        val reducedPowerThreshold: Float = 0f,
        val reduction: Float = 0f,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeFloat(1, fullPowerThreshold)
            writeFloat(2, reducedPowerThreshold)
            writeFloat(3, reduction)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): NotificationDebugEdging {
                var fullPowerThreshold = 0f
                var reducedPowerThreshold = 0f
                var reduction = 0f
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> fullPowerThreshold = readFloat()
                        2 -> reducedPowerThreshold = readFloat()
                        3 -> reduction = readFloat()
                        else -> skip(wireType)
                    }
                }
                return NotificationDebugEdging(
                    fullPowerThreshold, reducedPowerThreshold, reduction,
                )
            }
        }
    }

    data class NotificationDebugTeleplot(
        val id: String = "",
        val value: Float = 0f,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeString(1, id)
            writeFloat(2, value)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): NotificationDebugTeleplot {
                var id = ""
                var value = 0f
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> id = readString()
                        2 -> value = readFloat()
                        else -> skip(wireType)
                    }
                }
                return NotificationDebugTeleplot(id, value)
            }
        }
    }

    // ── focstim_rpc.proto ─────────────────────────────────────────────────

    data class Notification(
        val notificationBoot: NotificationBoot? = null,
        val notificationDeviceVolume: NotificationDeviceVolume? = null,
        val notificationCurrents: NotificationCurrents? = null,
        val notificationOutputResistance: NotificationOutputResistance? = null,
        val notificationSystemStats: NotificationSystemStats? = null,
        val notificationSignalStats: NotificationSignalStats? = null,
        val notificationBattery: NotificationBattery? = null,
        val notificationLsm6Dsox: NotificationLSM6DSOX? = null,
        val notificationPressure: NotificationPressure? = null,
        val notificationButtonPress: NotificationButtonPress? = null,
        val notificationSkinResistance: NotificationSkinResistance? = null,
        val timestamp: Long = 0L,
        val notificationDebugString: NotificationDebugString? = null,
        val notificationDebugAs5311: NotificationDebugAS5311? = null,
        val notificationDebugEdging: NotificationDebugEdging? = null,
        val notificationDebugTeleplot: NotificationDebugTeleplot? = null,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            notificationBoot?.let { writeMessage(1, it.toByteArray()) }
            notificationDeviceVolume?.let { writeMessage(2, it.toByteArray()) }
            notificationCurrents?.let { writeMessage(3, it.toByteArray()) }
            notificationOutputResistance?.let { writeMessage(4, it.toByteArray()) }
            notificationSystemStats?.let { writeMessage(5, it.toByteArray()) }
            notificationSignalStats?.let { writeMessage(6, it.toByteArray()) }
            notificationBattery?.let { writeMessage(7, it.toByteArray()) }
            notificationLsm6Dsox?.let { writeMessage(8, it.toByteArray()) }
            notificationPressure?.let { writeMessage(9, it.toByteArray()) }
            notificationButtonPress?.let { writeMessage(10, it.toByteArray()) }
            notificationSkinResistance?.let { writeMessage(11, it.toByteArray()) }
            writeUInt64(999, timestamp)
            notificationDebugString?.let { writeMessage(1000, it.toByteArray()) }
            notificationDebugAs5311?.let { writeMessage(1001, it.toByteArray()) }
            notificationDebugEdging?.let { writeMessage(1002, it.toByteArray()) }
            notificationDebugTeleplot?.let { writeMessage(1003, it.toByteArray()) }
        }

        companion object {
            fun parseFrom(bytes: ByteArray): Notification {
                var notificationBoot: NotificationBoot? = null
                var notificationDeviceVolume: NotificationDeviceVolume? = null
                var notificationCurrents: NotificationCurrents? = null
                var notificationOutputResistance: NotificationOutputResistance? = null
                var notificationSystemStats: NotificationSystemStats? = null
                var notificationSignalStats: NotificationSignalStats? = null
                var notificationBattery: NotificationBattery? = null
                var notificationLsm6Dsox: NotificationLSM6DSOX? = null
                var notificationPressure: NotificationPressure? = null
                var notificationButtonPress: NotificationButtonPress? = null
                var notificationSkinResistance: NotificationSkinResistance? = null
                var timestamp = 0L
                var notificationDebugString: NotificationDebugString? = null
                var notificationDebugAs5311: NotificationDebugAS5311? = null
                var notificationDebugEdging: NotificationDebugEdging? = null
                var notificationDebugTeleplot: NotificationDebugTeleplot? = null
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> notificationBoot = NotificationBoot.parseFrom(readBytes())
                        2 -> notificationDeviceVolume = NotificationDeviceVolume.parseFrom(readBytes())
                        3 -> notificationCurrents = NotificationCurrents.parseFrom(readBytes())
                        4 -> notificationOutputResistance = NotificationOutputResistance.parseFrom(readBytes())
                        5 -> notificationSystemStats = NotificationSystemStats.parseFrom(readBytes())
                        6 -> notificationSignalStats = NotificationSignalStats.parseFrom(readBytes())
                        7 -> notificationBattery = NotificationBattery.parseFrom(readBytes())
                        8 -> notificationLsm6Dsox = NotificationLSM6DSOX.parseFrom(readBytes())
                        9 -> notificationPressure = NotificationPressure.parseFrom(readBytes())
                        10 -> notificationButtonPress = NotificationButtonPress.parseFrom(readBytes())
                        11 -> notificationSkinResistance = NotificationSkinResistance.parseFrom(readBytes())
                        999 -> timestamp = readUInt64()
                        1000 -> notificationDebugString = NotificationDebugString.parseFrom(readBytes())
                        1001 -> notificationDebugAs5311 = NotificationDebugAS5311.parseFrom(readBytes())
                        1002 -> notificationDebugEdging = NotificationDebugEdging.parseFrom(readBytes())
                        1003 -> notificationDebugTeleplot = NotificationDebugTeleplot.parseFrom(readBytes())
                        else -> skip(wireType)
                    }
                }
                return Notification(
                    notificationBoot,
                    notificationDeviceVolume,
                    notificationCurrents,
                    notificationOutputResistance,
                    notificationSystemStats,
                    notificationSignalStats,
                    notificationBattery,
                    notificationLsm6Dsox,
                    notificationPressure,
                    notificationButtonPress,
                    notificationSkinResistance,
                    timestamp,
                    notificationDebugString,
                    notificationDebugAs5311,
                    notificationDebugEdging,
                    notificationDebugTeleplot,
                )
            }
        }
    }

    data class Request(
        val id: Int = 0,
        val requestAxisMoveTo: RequestAxisMoveTo? = null,
        val requestFirmwareVersion: RequestFirmwareVersion? = null,
        val requestCapabilitiesGet: RequestCapabilitiesGet? = null,
        val requestSignalStart: RequestSignalStart? = null,
        val requestSignalStop: RequestSignalStop? = null,
        val requestTimestampSet: RequestTimestampSet? = null,
        val requestTimestampGet: RequestTimestampGet? = null,
        val requestWifiParametersSet: RequestWifiParametersSet? = null,
        val requestWifiIpGet: RequestWifiIPGet? = null,
        val requestLockDeviceVolume: RequestLockDeviceVolume? = null,
        val requestLsm6DsoxStart: RequestLSM6DSOXStart? = null,
        val requestLsm6DsoxStop: RequestLSM6DSOXStop? = null,
        val requestDebugStm32DeepSleep: RequestDebugStm32DeepSleep? = null,
        val requestDebugEnterBootloader: RequestDebugEnterBootloader? = null,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeUInt32(1, id)
            requestAxisMoveTo?.let { writeMessage(5, it.toByteArray()) }
            requestFirmwareVersion?.let { writeMessage(500, it.toByteArray()) }
            requestCapabilitiesGet?.let { writeMessage(501, it.toByteArray()) }
            requestSignalStart?.let { writeMessage(502, it.toByteArray()) }
            requestSignalStop?.let { writeMessage(503, it.toByteArray()) }
            requestTimestampSet?.let { writeMessage(504, it.toByteArray()) }
            requestTimestampGet?.let { writeMessage(505, it.toByteArray()) }
            requestWifiParametersSet?.let { writeMessage(507, it.toByteArray()) }
            requestWifiIpGet?.let { writeMessage(508, it.toByteArray()) }
            requestLockDeviceVolume?.let { writeMessage(509, it.toByteArray()) }
            requestLsm6DsoxStart?.let { writeMessage(600, it.toByteArray()) }
            requestLsm6DsoxStop?.let { writeMessage(601, it.toByteArray()) }
            requestDebugStm32DeepSleep?.let { writeMessage(1000, it.toByteArray()) }
            requestDebugEnterBootloader?.let { writeMessage(1001, it.toByteArray()) }
        }

        companion object {
            fun parseFrom(bytes: ByteArray): Request {
                var id = 0
                var requestAxisMoveTo: RequestAxisMoveTo? = null
                var requestFirmwareVersion: RequestFirmwareVersion? = null
                var requestCapabilitiesGet: RequestCapabilitiesGet? = null
                var requestSignalStart: RequestSignalStart? = null
                var requestSignalStop: RequestSignalStop? = null
                var requestTimestampSet: RequestTimestampSet? = null
                var requestTimestampGet: RequestTimestampGet? = null
                var requestWifiParametersSet: RequestWifiParametersSet? = null
                var requestWifiIpGet: RequestWifiIPGet? = null
                var requestLockDeviceVolume: RequestLockDeviceVolume? = null
                var requestLsm6DsoxStart: RequestLSM6DSOXStart? = null
                var requestLsm6DsoxStop: RequestLSM6DSOXStop? = null
                var requestDebugStm32DeepSleep: RequestDebugStm32DeepSleep? = null
                var requestDebugEnterBootloader: RequestDebugEnterBootloader? = null
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> id = readUInt32().toInt()
                        5 -> requestAxisMoveTo = RequestAxisMoveTo.parseFrom(readBytes())
                        500 -> requestFirmwareVersion = RequestFirmwareVersion.parseFrom(readBytes())
                        501 -> requestCapabilitiesGet = RequestCapabilitiesGet.parseFrom(readBytes())
                        502 -> requestSignalStart = RequestSignalStart.parseFrom(readBytes())
                        503 -> requestSignalStop = RequestSignalStop.parseFrom(readBytes())
                        504 -> requestTimestampSet = RequestTimestampSet.parseFrom(readBytes())
                        505 -> requestTimestampGet = RequestTimestampGet.parseFrom(readBytes())
                        507 -> requestWifiParametersSet = RequestWifiParametersSet.parseFrom(readBytes())
                        508 -> requestWifiIpGet = RequestWifiIPGet.parseFrom(readBytes())
                        509 -> requestLockDeviceVolume = RequestLockDeviceVolume.parseFrom(readBytes())
                        600 -> requestLsm6DsoxStart = RequestLSM6DSOXStart.parseFrom(readBytes())
                        601 -> requestLsm6DsoxStop = RequestLSM6DSOXStop.parseFrom(readBytes())
                        1000 -> requestDebugStm32DeepSleep = RequestDebugStm32DeepSleep.parseFrom(readBytes())
                        1001 -> requestDebugEnterBootloader = RequestDebugEnterBootloader.parseFrom(readBytes())
                        else -> skip(wireType)
                    }
                }
                return Request(
                    id,
                    requestAxisMoveTo,
                    requestFirmwareVersion,
                    requestCapabilitiesGet,
                    requestSignalStart,
                    requestSignalStop,
                    requestTimestampSet,
                    requestTimestampGet,
                    requestWifiParametersSet,
                    requestWifiIpGet,
                    requestLockDeviceVolume,
                    requestLsm6DsoxStart,
                    requestLsm6DsoxStop,
                    requestDebugStm32DeepSleep,
                    requestDebugEnterBootloader,
                )
            }
        }
    }

    data class Response(
        val id: Int = 0,
        val error: Error? = null,
        val responseAxisMoveTo: ResponseAxisMoveTo? = null,
        val responseFirmwareVersion: ResponseFirmwareVersion? = null,
        val responseCapabilitiesGet: ResponseCapabilitiesGet? = null,
        val responseSignalStart: ResponseSignalStart? = null,
        val responseSignalStop: ResponseSignalStop? = null,
        val responseTimestampSet: ResponseTimestampSet? = null,
        val responseTimestampGet: ResponseTimestampGet? = null,
        val responseWifiParametersSet: ResponseWifiParametersSet? = null,
        val responseWifiIpGet: ResponseWifiIPGet? = null,
        val responseLockDeviceVolume: ResponseLockDeviceVolume? = null,
        val responseLsm6DsoxStart: ResponseLSM6DSOXStart? = null,
        val responseLsm6DsoxStop: ResponseLSM6DSOXStop? = null,
        val responseDebugStm32DeepSleep: ResponseDebugStm32DeepSleep? = null,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeUInt32(1, id)
            error?.let { writeMessage(3, it.toByteArray()) }
            responseAxisMoveTo?.let { writeMessage(5, it.toByteArray()) }
            responseFirmwareVersion?.let { writeMessage(500, it.toByteArray()) }
            responseCapabilitiesGet?.let { writeMessage(501, it.toByteArray()) }
            responseSignalStart?.let { writeMessage(502, it.toByteArray()) }
            responseSignalStop?.let { writeMessage(503, it.toByteArray()) }
            responseTimestampSet?.let { writeMessage(504, it.toByteArray()) }
            responseTimestampGet?.let { writeMessage(505, it.toByteArray()) }
            responseWifiParametersSet?.let { writeMessage(507, it.toByteArray()) }
            responseWifiIpGet?.let { writeMessage(508, it.toByteArray()) }
            responseLockDeviceVolume?.let { writeMessage(509, it.toByteArray()) }
            responseLsm6DsoxStart?.let { writeMessage(600, it.toByteArray()) }
            responseLsm6DsoxStop?.let { writeMessage(601, it.toByteArray()) }
            responseDebugStm32DeepSleep?.let { writeMessage(1000, it.toByteArray()) }
        }

        companion object {
            fun parseFrom(bytes: ByteArray): Response {
                var id = 0
                var error: Error? = null
                var responseAxisMoveTo: ResponseAxisMoveTo? = null
                var responseFirmwareVersion: ResponseFirmwareVersion? = null
                var responseCapabilitiesGet: ResponseCapabilitiesGet? = null
                var responseSignalStart: ResponseSignalStart? = null
                var responseSignalStop: ResponseSignalStop? = null
                var responseTimestampSet: ResponseTimestampSet? = null
                var responseTimestampGet: ResponseTimestampGet? = null
                var responseWifiParametersSet: ResponseWifiParametersSet? = null
                var responseWifiIpGet: ResponseWifiIPGet? = null
                var responseLockDeviceVolume: ResponseLockDeviceVolume? = null
                var responseLsm6DsoxStart: ResponseLSM6DSOXStart? = null
                var responseLsm6DsoxStop: ResponseLSM6DSOXStop? = null
                var responseDebugStm32DeepSleep: ResponseDebugStm32DeepSleep? = null
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> id = readUInt32().toInt()
                        3 -> error = Error.parseFrom(readBytes())
                        5 -> responseAxisMoveTo = ResponseAxisMoveTo.parseFrom(readBytes())
                        500 -> responseFirmwareVersion = ResponseFirmwareVersion.parseFrom(readBytes())
                        501 -> responseCapabilitiesGet = ResponseCapabilitiesGet.parseFrom(readBytes())
                        502 -> responseSignalStart = ResponseSignalStart.parseFrom(readBytes())
                        503 -> responseSignalStop = ResponseSignalStop.parseFrom(readBytes())
                        504 -> responseTimestampSet = ResponseTimestampSet.parseFrom(readBytes())
                        505 -> responseTimestampGet = ResponseTimestampGet.parseFrom(readBytes())
                        507 -> responseWifiParametersSet = ResponseWifiParametersSet.parseFrom(readBytes())
                        508 -> responseWifiIpGet = ResponseWifiIPGet.parseFrom(readBytes())
                        509 -> responseLockDeviceVolume = ResponseLockDeviceVolume.parseFrom(readBytes())
                        600 -> responseLsm6DsoxStart = ResponseLSM6DSOXStart.parseFrom(readBytes())
                        601 -> responseLsm6DsoxStop = ResponseLSM6DSOXStop.parseFrom(readBytes())
                        1000 -> responseDebugStm32DeepSleep = ResponseDebugStm32DeepSleep.parseFrom(readBytes())
                        else -> skip(wireType)
                    }
                }
                return Response(
                    id,
                    error,
                    responseAxisMoveTo,
                    responseFirmwareVersion,
                    responseCapabilitiesGet,
                    responseSignalStart,
                    responseSignalStop,
                    responseTimestampSet,
                    responseTimestampGet,
                    responseWifiParametersSet,
                    responseWifiIpGet,
                    responseLockDeviceVolume,
                    responseLsm6DsoxStart,
                    responseLsm6DsoxStop,
                    responseDebugStm32DeepSleep,
                )
            }
        }
    }

    data class Error(
        val code: Errors = Errors.ERROR_UNKNOWN,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            writeEnum(1, code.value)
        }

        companion object {
            fun parseFrom(bytes: ByteArray): Error {
                var code = Errors.ERROR_UNKNOWN
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        1 -> Errors.fromValue(readUInt32().toInt())?.let { code = it }
                        else -> skip(wireType)
                    }
                }
                return Error(code)
            }
        }
    }

    data class RpcMessage(
        val request: Request? = null,
        val response: Response? = null,
        val notification: Notification? = null,
    ) {
        fun toByteArray(): ByteArray = encodeMessage {
            request?.let { writeMessage(2, it.toByteArray()) }
            response?.let { writeMessage(4, it.toByteArray()) }
            notification?.let { writeMessage(5, it.toByteArray()) }
        }

        companion object {
            fun parseFrom(bytes: ByteArray): RpcMessage {
                var request: Request? = null
                var response: Response? = null
                var notification: Notification? = null
                ProtoReader(bytes).forEachField { field, wireType ->
                    when (field) {
                        2 -> request = Request.parseFrom(readBytes())
                        4 -> response = Response.parseFrom(readBytes())
                        5 -> notification = Notification.parseFrom(readBytes())
                        else -> skip(wireType)
                    }
                }
                return RpcMessage(request, response, notification)
            }
        }
    }
}

// ── Protobuf wire codec ───────────────────────────────────────────────────

private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LENGTH_DELIMITED = 2
private const val WIRE_FIXED32 = 5

private inline fun encodeMessage(block: ProtoWriter.() -> Unit): ByteArray {
    val writer = ProtoWriter()
    writer.block()
    return writer.toByteArray()
}

private inline fun ProtoReader.forEachField(block: ProtoReader.(field: Int, wireType: Int) -> Unit) {
    while (hasMore()) {
        val tag = readTag()
        block(tag ushr 3, tag and 0x07)
    }
}

private class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    fun writeUInt32(field: Int, value: Int) {
        if (value == 0) return
        writeTag(field, WIRE_VARINT)
        writeVarint(value.toLong() and 0xFFFFFFFFL)
    }

    fun writeUInt32(field: Int, value: Long) {
        if (value == 0L) return
        writeTag(field, WIRE_VARINT)
        writeVarint(value and 0xFFFFFFFFL)
    }

    fun writeUInt64(field: Int, value: Long) {
        if (value == 0L) return
        writeTag(field, WIRE_VARINT)
        writeVarint(value)
    }

    fun writeInt32(field: Int, value: Int) {
        if (value == 0) return
        writeTag(field, WIRE_VARINT)
        writeVarint(value.toLong())
    }

    fun writeInt64(field: Int, value: Long) {
        if (value == 0L) return
        writeTag(field, WIRE_VARINT)
        writeVarint(value)
    }

    fun writeSInt32(field: Int, value: Int) {
        if (value == 0) return
        writeTag(field, WIRE_VARINT)
        val zigZag = (value shl 1) xor (value shr 31)
        writeVarint(zigZag.toLong() and 0xFFFFFFFFL)
    }

    fun writeSInt64(field: Int, value: Long) {
        if (value == 0L) return
        writeTag(field, WIRE_VARINT)
        writeVarint((value shl 1) xor (value shr 63))
    }

    fun writeFixed32(field: Int, value: Long) {
        if (value == 0L) return
        writeTag(field, WIRE_FIXED32)
        writeFixed32(value)
    }

    fun writeFloat(field: Int, value: Float) {
        val bits = value.toRawBits()
        if (bits == 0) return
        writeTag(field, WIRE_FIXED32)
        writeFixed32(bits.toLong() and 0xFFFFFFFFL)
    }

    fun writeBool(field: Int, value: Boolean) {
        if (!value) return
        writeTag(field, WIRE_VARINT)
        writeVarint(1L)
    }

    fun writeString(field: Int, value: String) {
        if (value.isEmpty()) return
        writeBytes(field, value.toByteArray(Charsets.UTF_8))
    }

    fun writeBytes(field: Int, value: ByteArray) {
        if (value.isEmpty()) return
        writeTag(field, WIRE_LENGTH_DELIMITED)
        writeVarint(value.size.toLong())
        out.write(value)
    }

    fun writeEnum(field: Int, value: Int) {
        if (value == 0) return
        writeTag(field, WIRE_VARINT)
        writeVarint(value.toLong() and 0xFFFFFFFFL)
    }

    fun writeMessage(field: Int, payload: ByteArray) {
        writeTag(field, WIRE_LENGTH_DELIMITED)
        writeVarint(payload.size.toLong())
        out.write(payload)
    }

    private fun writeTag(field: Int, wireType: Int) {
        writeVarint(((field shl 3) or wireType).toLong())
    }

    private fun writeVarint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun writeFixed32(value: Long) {
        out.write((value and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 24) and 0xFF).toInt())
    }
}

private class ProtoReader(private val bytes: ByteArray) {
    private var position = 0

    fun hasMore(): Boolean = position < bytes.size

    fun readTag(): Int = readVarint().toInt()

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (position >= bytes.size) throw IllegalArgumentException("Truncated varint")
            val b = bytes[position++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift >= 64) throw IllegalArgumentException("Malformed varint")
        }
    }

    fun readUInt32(): Long = readVarint() and 0xFFFFFFFFL

    fun readUInt64(): Long = readVarint()

    fun readInt32(): Int = readVarint().toInt()

    fun readInt64(): Long = readVarint()

    fun readSInt32(): Int {
        val n = readVarint()
        return ((n ushr 1) xor -(n and 1L)).toInt()
    }

    fun readSInt64(): Long {
        val n = readVarint()
        return (n ushr 1) xor -(n and 1L)
    }

    fun readFixed32(): Long {
        if (bytes.size - position < 4) throw IllegalArgumentException("Truncated fixed32")
        var value = 0L
        for (i in 0 until 4) {
            value = value or ((bytes[position + i].toLong() and 0xFF) shl (8 * i))
        }
        position += 4
        return value
    }

    fun readFloat(): Float = Float.fromBits(readFixed32().toInt())

    fun readBool(): Boolean = readVarint() != 0L

    fun readString(): String = readBytes().toString(Charsets.UTF_8)

    fun readBytes(): ByteArray {
        val length = readVarint().toInt()
        if (length < 0 || length > bytes.size - position) {
            throw IllegalArgumentException("Truncated length-delimited field")
        }
        val result = bytes.copyOfRange(position, position + length)
        position += length
        return result
    }

    fun skip(wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> readVarint()
            WIRE_FIXED64 -> skipBytes(8)
            WIRE_LENGTH_DELIMITED -> skipBytes(readVarint().toInt())
            WIRE_FIXED32 -> skipBytes(4)
            else -> throw IllegalArgumentException("Unsupported wire type $wireType")
        }
    }

    private fun skipBytes(count: Int) {
        if (count < 0 || count > bytes.size - position) {
            throw IllegalArgumentException("Truncated field")
        }
        position += count
    }
}
