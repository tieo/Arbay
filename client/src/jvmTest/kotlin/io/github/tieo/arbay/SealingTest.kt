package io.github.tieo.arbay

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographyProviderApi
import dev.whyoleg.cryptography.algorithms.XDH
import io.github.tieo.arbay.api.Sealing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class SealingTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** RFC 9180, A.1.1: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-128-GCM, base mode, the first
     *  message. With the RFC's ephemeral key, the sealing gives the RFC's encapsulated key and
     *  ciphertext, so Neuland's HPKE opens what the app seals. */
    @Test
    fun `sealing gives the rfc's encapsulated key and ciphertext`() = runTest {
        val xdh = CryptographyProvider.Default.get(XDH)
        val skE = xdh.privateKeyDecoder(XDH.Curve.X25519).decodeFromByteArray(XDH.PrivateKey.Format.RAW, hex("52c4a758a802cd8b936eceea314432798d5baf2d7e9235dc084ab1b9cfa2f736"))
        val pkE = xdh.publicKeyDecoder(XDH.Curve.X25519).decodeFromByteArray(XDH.PublicKey.Format.RAW, hex("37fda3567bdbd628e88668c3c8d7e97d1d1253b6d4ea6d44c150f741f1bf4431"))
        @OptIn(CryptographyProviderApi::class)
        val ephemeral = object : XDH.KeyPair {
            override val privateKey = skE
            override val publicKey = pkE
        }
        val sealed = Sealing.seal(
            hex("3948cfe0ad1ddb695d780e59077195da6c56506b027329794ab02bca80815c4d"), ephemeral,
            hex("4265617574792069732074727574682c20747275746820626561757479"),
            hex("4f6465206f6e2061204772656369616e2055726e"), hex("436f756e742d30"),
        )
        assertEquals("37fda3567bdbd628e88668c3c8d7e97d1d1253b6d4ea6d44c150f741f1bf4431", sealed.copyOfRange(0, 32).hex())
        assertEquals("f938558b5d72f1a23810b4be2ab4f84331acc02fc97babc53a52ae8218a355a96d8770ac83d07bea87e13c512a", sealed.copyOfRange(32, sealed.size).hex())
    }
}
