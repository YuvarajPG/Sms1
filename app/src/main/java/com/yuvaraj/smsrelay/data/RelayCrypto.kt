package com.yuvaraj.smsrelay.data

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing codes are random, high-entropy secrets. The room path is a SHA-256
 * digest of the code; message bodies are encrypted locally with AES-GCM.
 */
object RelayCrypto {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val ITERATIONS = 180_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val SALT = "sms-relay-v1"

    fun newPairingCode(length: Int = 16): String {
        require(length >= 16) { "Pairing codes must be at least 16 characters." }
        val random = SecureRandom()
        return buildString(length) {
            repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
    }

    private fun normalized(code: String): String =
        code.filterNot { it.isWhitespace() }.uppercase()

    fun roomId(code: String): String {
        require(normalized(code).length >= 16) { "Invalid pairing code." }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized(code).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun key(code: String): SecretKeySpec {
        val spec = PBEKeySpec(normalized(code).toCharArray(), SALT.toByteArray(), ITERATIONS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    data class EncryptedMessage(val nonce: String, val ciphertext: String)

    fun encrypt(code: String, plainText: String): EncryptedMessage {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(code), GCMParameterSpec(TAG_BITS, nonce))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return EncryptedMessage(
            Base64.encodeToString(nonce, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        )
    }

    fun decrypt(code: String, nonce: String, ciphertext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(code),
            GCMParameterSpec(TAG_BITS, Base64.decode(nonce, Base64.NO_WRAP))
        )
        return cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
}
