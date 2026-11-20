package com.p2pchat.app.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class EcdhSharedSecretProviderTest {
    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @Test
    fun Aの秘密鍵とBの公開鍵はBの秘密鍵とAの公開鍵と同じ共有秘密になる() {
        val a = newKeyPair()
        val b = newKeyPair()

        val fromA = EcdhSharedSecretProvider { a.private }.deriveSecret(b.public)
        val fromB = EcdhSharedSecretProvider { b.private }.deriveSecret(a.public)

        assertEquals(32, fromA.size)
        assertArrayEquals(fromA, fromB)
    }

    @Test
    fun 相手の鍵が違えば共有秘密も違う() {
        val a = newKeyPair()
        val provider = EcdhSharedSecretProvider { a.private }

        val withB = provider.deriveSecret(newKeyPair().public)
        val withC = provider.deriveSecret(newKeyPair().public)

        assertFalse(withB.contentEquals(withC))
    }
}
