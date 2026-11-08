package com.yuvaraj.smsrelay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RelayCryptoTest {
    @Test fun encryptThenDecryptReturnsOriginalText() {
        val code = "ABCDEFGHIJKLMNOP"
        val plain = "Your verification code is 123456"
        val encrypted = RelayCrypto.encrypt(code, plain)
        assertEquals(plain, RelayCrypto.decrypt(code, encrypted.nonce, encrypted.ciphertext))
    }

    @Test fun eachEncryptionUsesANewNonce() {
        val code = "ABCDEFGHIJKLMNOP"
        val first = RelayCrypto.encrypt(code, "same message")
        val second = RelayCrypto.encrypt(code, "same message")
        assertNotEquals(first.nonce, second.nonce)
    }

    @Test(expected = Exception::class)
    fun wrongCodeCannotDecryptMessage() {
        val encrypted = RelayCrypto.encrypt("ABCDEFGHIJKLMNOP", "secret")
        RelayCrypto.decrypt("QRSTUVWXYZ234567", encrypted.nonce, encrypted.ciphertext)
    }
}
