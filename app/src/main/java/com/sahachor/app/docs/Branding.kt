package com.sahachor.app.docs

/** PNG logo bytes plus pixel size (read from the PNG header), used to keep the aspect ratio in every format. */
class Logo private constructor(val png: ByteArray, val width: Int, val height: Int) {
    companion object {
        private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        fun parse(bytes: ByteArray): Logo? {
            if (bytes.size < 24 || !(0 until 8).all { bytes[it] == signature[it] }) return null
            fun int(at: Int) = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
            val w = int(16); val h = int(20)
            return if (w > 0 && h > 0) Logo(bytes, w, h) else null
        }
    }
}

object Entitlements {
    private val state = kotlinx.coroutines.flow.MutableStateFlow(false)
    val proState: kotlinx.coroutines.flow.StateFlow<Boolean> get() = state

    /**
     * Paid "remove branding" tier. False until Google Play Billing confirms a subscription (see BillingManager).
     * The AI tools get no way to switch branding off; only this flag can.
     */
    val isPro: Boolean get() = state.value

    fun set(pro: Boolean) { state.value = pro }
}

object Branding {
    const val NAME = "LIFE EASY"
    fun enabled(): Boolean = !Entitlements.isPro
}
