package io.github.sepfins.limitguard.internal.hashing

import java.security.MessageDigest
import java.util.HexFormat

internal object StableJsonHasher {
    const val SHA_256 = "SHA-256"

    fun sha256Hex(canonicalJson: String): String {
        val digest = MessageDigest.getInstance(SHA_256)
        val hashBytes = digest.digest(canonicalJson.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(hashBytes)
    }
}
