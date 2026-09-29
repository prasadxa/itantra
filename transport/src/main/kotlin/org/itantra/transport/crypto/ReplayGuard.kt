package org.itantra.transport.crypto

/**
 * Rejects a replayed `(senderShortId, nonce)` pair within a bounded window. One instance per
 * [SecureSession] (i.e. per receiving direction of a paired link) — [capacity] bounds memory for a
 * long-running session; the oldest nonce is evicted once full (LRU by insertion order).
 */
class ReplayGuard(private val capacity: Int = 512) {
    private val order = ArrayDeque<String>()
    private val seen = HashSet<String>()

    /** Returns true the first time this nonce is seen for this sender (accept); false on replay. */
    @Synchronized
    fun accept(senderShortId: Int, nonce: ByteArray): Boolean {
        val key = keyOf(senderShortId, nonce)
        if (!seen.add(key)) return false
        order.addLast(key)
        if (order.size > capacity) {
            seen.remove(order.removeFirst())
        }
        return true
    }

    private fun keyOf(senderShortId: Int, nonce: ByteArray): String {
        val sb = StringBuilder(2 + nonce.size * 2)
        sb.append(senderShortId).append(':')
        for (b in nonce) sb.append("%02x".format(b))
        return sb.toString()
    }
}
