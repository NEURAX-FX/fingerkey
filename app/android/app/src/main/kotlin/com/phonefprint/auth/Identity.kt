package com.phonefprint.auth

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Base64
import java.security.SecureRandom

/** Raw Ed25519 seed, implemented without a platform Ed25519 provider (API 24+). */
class Identity internal constructor(seed: ByteArray) {
    private val key = Ed25519PrivateKeyParameters(seed.also {
        require(it.size == Ed25519PrivateKeyParameters.KEY_SIZE) { "Invalid Ed25519 seed length" }
    }, 0)

    val publicKeyBase64: String = Base64.toBase64String(key.generatePublicKey().encoded)

    fun sign(message: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, key)
        update(message, 0, message.size)
        generateSignature()
    }

    internal fun seed(): ByteArray = key.encoded

    internal companion object {
        fun generate(): Identity = Identity(Ed25519PrivateKeyParameters(SecureRandom()).encoded)
    }
}
