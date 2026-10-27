package fi.tunturi.rows.ftms

/** Fitness Machine Control Point (0x2AD9) commands and its response indication. */
object FtmsControl {
    const val OP_REQUEST_CONTROL = 0x00
    const val OP_RESET = 0x01
    const val OP_TARGET_RESISTANCE = 0x04
    const val OP_TARGET_POWER = 0x05
    const val OP_TARGET_HEART_RATE = 0x06
    const val OP_START_RESUME = 0x07
    const val OP_STOP_PAUSE = 0x08
    const val OP_TARGET_CADENCE = 0x09

    /** Control is per-connection: request it again after every reconnect. */
    fun requestControl(): ByteArray = byteArrayOf(OP_REQUEST_CONTROL.toByte())

    fun reset(): ByteArray = byteArrayOf(OP_RESET.toByte())

    fun startResume(): ByteArray = byteArrayOf(OP_START_RESUME.toByte())

    fun stop(): ByteArray = byteArrayOf(OP_STOP_PAUSE.toByte(), 0x01)

    fun pause(): ByteArray = byteArrayOf(OP_STOP_PAUSE.toByte(), 0x02)

    /**
     * shortcut: encoded as sint16 x0.1 per the SIG table. AppDevice's SDK writes a
     * single byte here, which suggests at least some consoles want 1 byte — flip
     * this if the R60/R80 answers Invalid Parameter (0x03).
     */
    fun targetResistanceLevel(tenths: Int): ByteArray = withOp(OP_TARGET_RESISTANCE) { u16(tenths) }

    fun targetPower(watts: Int): ByteArray = withOp(OP_TARGET_POWER) { u16(watts) }

    fun targetHeartRate(bpm: Int): ByteArray = byteArrayOf(OP_TARGET_HEART_RATE.toByte(), bpm.toByte())

    /**
     * Cadence resolution is 0.5 strokes/min.
     *
     * shortcut: SIG assigns 0x09 to targeted cadence, but the vendor SDK in their app
     * uses 0x14 for it (and 0x09 for targeted energy). If the machine answers
     * "opcode not supported", try 0x14 before assuming cadence is unsupported.
     */
    fun targetCadence(halfStrokesPerMin: Int): ByteArray = withOp(OP_TARGET_CADENCE) { u16(halfStrokesPerMin) }

    private inline fun withOp(op: Int, body: ByteArray.() -> Unit) =
        ByteArray(3).also { arr ->
            arr[0] = op.toByte()
            val payload = ByteArray(2).apply(body)
            arr[1] = payload[0]
            arr[2] = payload[1]
        }

    private fun ByteArray.u16(v: Int) {
        this[0] = (v and 0xFF).toByte()
        this[1] = ((v shr 8) and 0xFF).toByte()
    }
}

/** Three-byte indication: 0x80, the opcode being answered, then the result code. */
data class ControlResponse(val requestOp: Int, val result: Int) {
    val success: Boolean get() = result == RESULT_SUCCESS

    val message: String
        get() = when (result) {
            RESULT_SUCCESS -> "ok"
            RESULT_NOT_SUPPORTED -> "opcode not supported"
            RESULT_INVALID_PARAMETER -> "invalid parameter"
            RESULT_OPERATION_FAILED -> "operation failed"
            RESULT_CONTROL_NOT_PERMITTED -> "control not permitted (request control first)"
            else -> "unknown result $result"
        }

    companion object {
        const val RESULT_SUCCESS = 0x01
        const val RESULT_NOT_SUPPORTED = 0x02
        const val RESULT_INVALID_PARAMETER = 0x03
        const val RESULT_OPERATION_FAILED = 0x04
        const val RESULT_CONTROL_NOT_PERMITTED = 0x05

        fun parse(bytes: ByteArray): ControlResponse? {
            if (bytes.size < 3 || bytes[0].toInt() and 0xFF != 0x80) return null
            return ControlResponse(bytes[1].toInt() and 0xFF, bytes[2].toInt() and 0xFF)
        }
    }
}
