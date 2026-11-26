package com.riderlink.intercom.protocol

import java.security.SecureRandom

object SessionToken {
    private val random = SecureRandom()

    /** Generates a 4-digit session token in 1000..9999 (fits in uint16). */
    fun generate(): Int = 1000 + random.nextInt(9000)

    fun format(token: Int): String = (token and 0xFFFF).toString().padStart(4, '0')

    fun parse(raw: String): Int? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 5) return null
        val parsed = trimmed.toIntOrNull() ?: return null
        return if (parsed in 0..65535) parsed else null
    }
}
